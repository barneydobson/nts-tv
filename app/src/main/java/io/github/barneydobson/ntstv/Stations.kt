package io.github.barneydobson.ntstv

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

/** A broadcast on one of the live channels, as described by the NTS API. */
data class Show(
    val title: String,
    val location: String?,
    val start: Instant?,
    val end: Instant?,
    val genres: List<String>,
    val imageUrl: String?,
)

data class LiveChannel(val channel: String, val now: Show?, val next: Show?)

/** One of the NTS "Infinite Mixtapes" streams. */
data class Mixtape(
    val alias: String,
    val title: String,
    val subtitle: String?,
    val streamUrl: String,
    val imageUrl: String?,
)

enum class PlayState { PLAYING, BUFFERING, PAUSED, ERROR }

/**
 * Stream URLs and the mapping between stations and Media3 [MediaItem]s.
 *
 * Media IDs are `live:<channel>` for the two live channels and `mixtape:<alias>` for mixtapes.
 */
object Stations {
    private const val LIVE_PREFIX = "live:"
    private const val MIXTAPE_PREFIX = "mixtape:"

    private val LIVE_STREAMS = mapOf(
        "1" to "https://stream-relay-geo.ntslive.net/stream",
        "2" to "https://stream-relay-geo.ntslive.net/stream2",
    )

    /**
     * Only NTS's own hosts, over HTTPS, are ever fetched: the API, its image CDN and its
     * stream relays. Anything else (other hosts, http:, file:, content:, ...) is rejected,
     * whether it comes from the API or from a media controller.
     */
    private val TRUSTED_HOSTS = listOf("nts.live", "ntslive.net", "ntslive.co.uk")

    fun isTrustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun liveId(channel: String) = LIVE_PREFIX + channel

    fun mixtapeId(alias: String) = MIXTAPE_PREFIX + alias

    fun liveChannelOf(mediaId: String?): String? =
        mediaId?.takeIf { it.startsWith(LIVE_PREFIX) }?.removePrefix(LIVE_PREFIX)

    fun mixtapeAliasOf(mediaId: String?): String? =
        mediaId?.takeIf { it.startsWith(MIXTAPE_PREFIX) }?.removePrefix(MIXTAPE_PREFIX)

    /** The stream URL for media IDs whose URL never changes (the live channels). */
    fun knownStreamUrl(mediaId: String): String? = liveChannelOf(mediaId)?.let { LIVE_STREAMS[it] }

    fun liveItem(channel: String, show: Show?): MediaItem =
        buildItem(liveId(channel), LIVE_STREAMS.getValue(channel), liveMetadata(channel, show))

    fun liveMetadata(channel: String, show: Show?): MediaMetadata = MediaMetadata.Builder()
        .setTitle(show?.title ?: "NTS $channel")
        .setArtist(listOfNotNull("NTS $channel", show?.location).joinToString(" · "))
        .setStation("NTS $channel")
        .setArtworkUri(show?.imageUrl?.let(Uri::parse))
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .build()

    fun mixtapeItem(mixtape: Mixtape): MediaItem = buildItem(
        mixtapeId(mixtape.alias),
        mixtape.streamUrl,
        MediaMetadata.Builder()
            .setTitle(mixtape.title)
            .setArtist(mixtape.subtitle ?: "NTS Infinite Mixtapes")
            .setStation("NTS Mixtapes")
            .setArtworkUri(mixtape.imageUrl?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build(),
    )

    private fun buildItem(mediaId: String, url: String, metadata: MediaMetadata): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(url)
            // The URI itself is dropped when a MediaItem crosses from controller to session,
            // so it is also carried in the request metadata (see PlaybackService.onAddMediaItems).
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).build(),
            )
            .setMediaMetadata(metadata)
            .build()
}
