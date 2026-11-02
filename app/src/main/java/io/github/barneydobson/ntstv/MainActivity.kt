package io.github.barneydobson.ntstv

import android.content.ComponentName
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var focusPlayingOnConnect = true

    private lateinit var cards: Map<String, ChannelCard>
    private lateinit var mixtapeAdapter: MixtapeAdapter
    private lateinit var mixtapeRow: RecyclerView
    private lateinit var mixtapeStatus: TextView
    private lateinit var mixtapeCaption: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var updateButton: TextView
    private lateinit var backdrop: ImageView

    private var live: List<LiveChannel> = emptyList()
    private var mixtapes: List<Mixtape> = emptyList()
    private var focusedId: String? = null
    private var backdropUrl: String? = null

    private val timeFormat by lazy {
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a")
            .withZone(ZoneId.systemDefault())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        backdrop = findViewById(R.id.backdrop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP))
        } else {
            backdrop.alpha = 0.2f
        }
        nowPlaying = findViewById(R.id.now_playing)
        updateButton = findViewById(R.id.update_button)
        updateButton.setOnClickListener { installUpdateFromButton() }
        mixtapeStatus = findViewById(R.id.mixtapes_status)
        mixtapeCaption = findViewById(R.id.mixtape_caption)
        findViewById<TextView>(R.id.footer).text = getString(R.string.footer, BuildConfig.VERSION_NAME)

        cards = mapOf(
            "1" to ChannelCard("1", findViewById(R.id.channel1)),
            "2" to ChannelCard("2", findViewById(R.id.channel2)),
        )

        mixtapeAdapter = MixtapeAdapter(
            onClick = { mixtape ->
                playOrToggle(Stations.mixtapeId(mixtape.alias)) { Stations.mixtapeItem(mixtape) }
            },
            onFocus = { mixtape ->
                mixtapeCaption.text = mixtape?.let { listOfNotNull(it.title, it.subtitle).joinToString(" — ") }
                if (mixtape != null) onItemFocused(Stations.mixtapeId(mixtape.alias))
            },
        )
        mixtapeRow = findViewById<RecyclerView>(R.id.mixtapes).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            adapter = mixtapeAdapter
            itemAnimator = null
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { pollLiveSchedule() }
                launch { loadMixtapes() }
                launch { Updater.state.collect { renderUpdate(it) } }
                launch { checkForUpdate() }
            }
        }

        render()
        cards.getValue("1").root.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            val connected = try {
                future.get()
            } catch (e: Exception) {
                return@addListener
            }
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = render()
            })
            render()
            if (focusPlayingOnConnect) {
                focusPlayingOnConnect = false
                focusCurrentStation()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val c = controller ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                if (c.playWhenReady && c.playerError == null) c.pause() else resume(c)
            KeyEvent.KEYCODE_MEDIA_PLAY -> resume(c)
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> c.pause()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // ---- Playback ----

    private fun playOrToggle(mediaId: String, item: () -> MediaItem) {
        val c = controller ?: return
        val sameStation = c.currentMediaItem?.mediaId == mediaId
        if (sameStation && c.playerError == null && c.playbackState != Player.STATE_IDLE) {
            if (c.playWhenReady) c.pause() else c.play()
        } else {
            c.setMediaItem(item())
            c.prepare()
            c.play()
        }
    }

    private fun resume(c: Player) {
        if (c.currentMediaItem == null) return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    private fun playState(c: Player): PlayState = when {
        c.playerError != null -> PlayState.ERROR
        c.isPlaying -> PlayState.PLAYING
        c.playWhenReady && c.playbackState != Player.STATE_READY -> PlayState.BUFFERING
        else -> PlayState.PAUSED
    }

    // ---- Updates ----

    /**
     * Installs a new release straight away if nothing is playing (the app closes and the user
     * reopens it); otherwise offers an "Update" button rather than cutting off the music.
     */
    private suspend fun checkForUpdate() {
        val update = Updater.check(applicationContext) ?: return
        val connected = withTimeoutOrNull(10_000L) {
            while (controller == null) delay(100)
            controller
        }
        if (connected != null && !connected.playWhenReady && Updater.state.value is UpdateState.Ready) {
            Updater.install(applicationContext, update)
        }
    }

    private fun installUpdateFromButton() {
        val update = when (val state = Updater.state.value) {
            is UpdateState.Ready -> state.update
            is UpdateState.Failed -> state.update
            else -> return
        }
        lifecycleScope.launch { Updater.install(applicationContext, update) }
    }

    private fun renderUpdate(state: UpdateState) {
        val (text, actionable) = when (state) {
            UpdateState.None -> null to false
            is UpdateState.Ready -> getString(R.string.update_ready, state.update.version) to true
            is UpdateState.Installing -> getString(R.string.update_installing, state.update.version) to false
            is UpdateState.Failed -> getString(R.string.update_failed, state.update.version) to true
        }
        updateButton.isVisible = text != null
        updateButton.text = text
        updateButton.isFocusable = actionable
        updateButton.isClickable = actionable
    }

    // ---- Data ----

    private suspend fun pollLiveSchedule() {
        while (true) {
            val ok = try {
                live = NtsApi.fetchLive()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            render()
            delay(if (ok) 60_000L else 15_000L)
        }
    }

    private suspend fun loadMixtapes() {
        var wait = 5_000L
        while (mixtapes.isEmpty()) {
            try {
                mixtapes = NtsApi.fetchMixtapes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mixtapeStatus.setText(R.string.mixtapes_unavailable)
            }
            if (mixtapes.isEmpty()) {
                delay(wait)
                wait = (wait * 2).coerceAtMost(120_000L)
            }
        }
        mixtapeStatus.isVisible = false
        mixtapeAdapter.submit(mixtapes)
        render()
    }

    // ---- UI ----

    private fun render() {
        val c = controller
        val currentId = c?.currentMediaItem?.mediaId
        val state = c?.let(::playState)

        for ((channel, card) in cards) {
            val isCurrent = currentId == Stations.liveId(channel)
            card.bind(live.firstOrNull { it.channel == channel }, if (isCurrent) state else null)
        }
        mixtapeAdapter.setPlaying(Stations.mixtapeAliasOf(currentId), state)

        val metadata = c?.mediaMetadata
        if (c?.currentMediaItem != null && metadata != null && state != null) {
            val label = getString(
                when (state) {
                    PlayState.PLAYING -> R.string.now_playing
                    PlayState.BUFFERING -> R.string.tuning_in
                    PlayState.PAUSED -> R.string.paused
                    PlayState.ERROR -> R.string.reconnecting
                },
            )
            val what = listOfNotNull(metadata.station, metadata.title).joinToString(" — ")
            nowPlaying.text = getString(R.string.now_playing_line, label, what)
            nowPlaying.isVisible = true
        } else {
            nowPlaying.isVisible = false
        }

        updateBackdrop()
    }

    private fun onItemFocused(mediaId: String) {
        focusedId = mediaId
        updateBackdrop()
    }

    private fun imageFor(mediaId: String?): String? {
        Stations.liveChannelOf(mediaId)?.let { ch ->
            return live.firstOrNull { it.channel == ch }?.now?.imageUrl
        }
        Stations.mixtapeAliasOf(mediaId)?.let { alias ->
            return mixtapes.firstOrNull { it.alias == alias }?.imageUrl
        }
        return null
    }

    private fun updateBackdrop() {
        val url = imageFor(focusedId) ?: imageFor(controller?.currentMediaItem?.mediaId)
        if (url == backdropUrl) return
        backdropUrl = url
        if (url == null) {
            backdrop.setImageDrawable(null)
        } else {
            backdrop.load(url) { crossfade(600) }
        }
    }

    private fun focusCurrentStation() {
        val id = controller?.currentMediaItem?.mediaId ?: return
        Stations.liveChannelOf(id)?.let { cards[it]?.root?.requestFocus() }
    }

    private fun formatTime(instant: Instant): String = timeFormat.format(instant)

    /** Views for one live channel card. */
    private inner class ChannelCard(val channel: String, val root: View) {
        private val art: ImageView = root.findViewById(R.id.art)
        private val number: TextView = root.findViewById(R.id.channel_number)
        private val meta: TextView = root.findViewById(R.id.meta)
        private val title: TextView = root.findViewById(R.id.title)
        private val genres: TextView = root.findViewById(R.id.genres)
        private val progressRow: View = root.findViewById(R.id.progress_row)
        private val startTime: TextView = root.findViewById(R.id.start_time)
        private val endTime: TextView = root.findViewById(R.id.end_time)
        private val progress: ProgressBar = root.findViewById(R.id.progress)
        private val next: TextView = root.findViewById(R.id.next)
        private val status: TextView = root.findViewById(R.id.status)

        private var imageUrl: String? = null
        private var lastData: LiveChannel? = null
        private var lastState: PlayState? = null

        init {
            number.text = channel
            art.clipToOutline = true
            root.setOnClickListener {
                playOrToggle(Stations.liveId(channel)) {
                    Stations.liveItem(channel, live.firstOrNull { it.channel == channel }?.now)
                }
            }
            root.setOnFocusChangeListener { v, hasFocus ->
                v.animateFocus(hasFocus, 1.03f)
                if (hasFocus) onItemFocused(Stations.liveId(channel))
                bind(lastData, lastState)
            }
        }

        fun bind(data: LiveChannel?, state: PlayState?) {
            lastData = data
            lastState = state
            val show = data?.now

            title.text = show?.title ?: getString(R.string.channel_fallback_title, channel)

            meta.text = show?.location.orEmpty()

            genres.text = show?.genres.orEmpty().take(3).joinToString(" · ")
            genres.isVisible = genres.text.isNotEmpty()

            val fraction = showProgress(show)
            progressRow.isVisible = fraction != null
            if (show?.start != null && show.end != null && fraction != null) {
                startTime.text = formatTime(show.start)
                endTime.text = formatTime(show.end)
                progress.progress = (fraction * 1000).toInt()
            }

            val upNext = data?.next
            next.text = upNext?.let {
                val at = it.start?.let(::formatTime)
                listOfNotNull(getString(R.string.next), at, it.title).joinToString(" · ")
            }.orEmpty()
            next.isVisible = next.text.isNotEmpty()

            status.text = when (state) {
                PlayState.PLAYING -> getString(R.string.status_playing)
                PlayState.BUFFERING -> getString(R.string.status_buffering)
                PlayState.PAUSED -> getString(R.string.status_paused)
                PlayState.ERROR -> getString(R.string.status_error)
                null -> if (root.hasFocus()) getString(R.string.status_listen) else ""
            }
            status.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (state == PlayState.PLAYING) R.drawable.ic_equalizer else 0, 0, 0, 0,
            )
            root.isActivated = state != null

            val url = show?.imageUrl
            if (url != imageUrl) {
                imageUrl = url
                art.load(url) {
                    crossfade(true)
                    placeholder(R.drawable.art_placeholder)
                    error(R.drawable.art_placeholder)
                }
            }
        }

        private fun showProgress(show: Show?): Float? {
            val start = show?.start ?: return null
            val end = show.end ?: return null
            val total = end.toEpochMilli() - start.toEpochMilli()
            if (total <= 0) return null
            val elapsed = System.currentTimeMillis() - start.toEpochMilli()
            return (elapsed.toFloat() / total).coerceIn(0f, 1f)
        }
    }
}

internal fun View.animateFocus(focused: Boolean, scale: Float) {
    val target = if (focused) scale else 1f
    animate().scaleX(target).scaleY(target).setDuration(150).start()
}
