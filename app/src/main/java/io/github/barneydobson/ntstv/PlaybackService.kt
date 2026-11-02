package io.github.barneydobson.ntstv

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/**
 * Owns the player so audio keeps going when the app is in the background or the TV's
 * screensaver kicks in. Also exposes a MediaSession so the system's media controls work.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    private val scope = MainScope()
    private val refreshNow = Channel<Unit>(Channel.CONFLATED)

    private var retryJob: Job? = null
    private var retryAttempt = 0
    private var currentMediaId: String? = null
    private var pausedAt = 0L

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // HTTP(S) only: no file/content/asset schemes, and no https -> http redirects.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(NtsApi.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerListener())

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        scope.launch { keepLiveMetadataFresh() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // Internet radio drops out now and then; keep trying with backoff (2s .. 60s).
            if (retryAttempt >= MAX_RETRIES) return
            val wait = (2_000L shl retryAttempt.coerceAtMost(5)).coerceAtMost(60_000L)
            retryAttempt++
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(wait)
                if (player.playerError != null && player.mediaItemCount > 0) {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retryAttempt = 0
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAt = SystemClock.elapsedRealtime()
            } else if (pausedAt != 0L &&
                SystemClock.elapsedRealtime() - pausedAt > RESYNC_AFTER_PAUSE_MS
            ) {
                // After a long pause, rejoin the live broadcast instead of resuming stale audio.
                pausedAt = 0L
                player.seekToDefaultPosition()
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (id != currentMediaId) {
                currentMediaId = id
                retryAttempt = 0
                retryJob?.cancel()
                pausedAt = 0L
                refreshNow.trySend(Unit)
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // Controllers can't be trusted to supply URLs: live channels always use the
            // built-in stream URLs, and anything else must be an HTTPS URL on NTS's hosts.
            val resolved = mediaItems.mapNotNull { item ->
                val uri = when {
                    Stations.liveChannelOf(item.mediaId) != null ->
                        Stations.knownStreamUrl(item.mediaId)
                    Stations.mixtapeAliasOf(item.mediaId) != null ->
                        (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)
                            ?.toString()
                            ?.takeIf(Stations::isTrustedUrl)
                    else -> null
                } ?: return@mapNotNull null

                val artwork = item.mediaMetadata.artworkUri?.takeIf { Stations.isTrustedUrl(it.toString()) }
                item.buildUpon()
                    .setUri(Uri.parse(uri))
                    .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(artwork).build())
                    .build()
            }
            if (resolved.size != mediaItems.size) {
                return Futures.immediateFailedFuture(
                    UnsupportedOperationException("Only NTS stations can be played"),
                )
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }
    }

    /** While a live channel is playing, keep the session's "now playing" info in step with the schedule. */
    private suspend fun keepLiveMetadataFresh() {
        while (true) {
            val wait = updateLiveMetadata()
            withTimeoutOrNull(wait) { refreshNow.receive() }
        }
    }

    /** Returns how long to wait before checking again. */
    private suspend fun updateLiveMetadata(): Long {
        val channel = Stations.liveChannelOf(player.currentMediaItem?.mediaId)
        if (channel == null || !player.playWhenReady) return IDLE_CHECK_MS

        val show = try {
            NtsApi.fetchLive().firstOrNull { it.channel == channel }?.now
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RETRY_FETCH_MS
        }

        val current = player.currentMediaItem ?: return IDLE_CHECK_MS
        if (Stations.liveChannelOf(current.mediaId) != channel) return 0L // switched meanwhile

        val metadata = Stations.liveMetadata(channel, show)
        if (metadata != current.mediaMetadata) {
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                current.buildUpon().setMediaMetadata(metadata).build(),
            )
        }

        // Check again just after this show is due to end (and at least every few minutes).
        val untilEnd = show?.end?.let { Duration.between(Instant.now(), it).toMillis() + 20_000 }
        return (untilEnd ?: LIVE_CHECK_MS).coerceIn(30_000L, LIVE_CHECK_MS)
    }

    private companion object {
        const val MAX_RETRIES = 12
        const val RESYNC_AFTER_PAUSE_MS = 30_000L
        const val IDLE_CHECK_MS = 60_000L
        const val LIVE_CHECK_MS = 5 * 60_000L
        const val RETRY_FETCH_MS = 30_000L
    }
}
