package io.github.barneydobson.ntstv

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

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

    private lateinit var settings: AppSettings
    private lateinit var content: ViewGroup
    private lateinit var settingsButton: View
    private lateinit var settingsOverlay: View
    private lateinit var volumeRow: View
    private lateinit var volumeValue: TextView
    private lateinit var volumeBar: LinearLayout
    private lateinit var volumeHint: TextView
    private lateinit var darkValue: TextView
    private lateinit var darkOverlay: View
    private lateinit var darkMark: View
    private lateinit var darkDot: View

    private var darkMode = false
    private var idleJob: Job? = null
    private var driftJob: Job? = null
    private var wasPlaying = false

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
        setUpSettingsAndDarkMode()

        cards = mapOf(
            "1" to ChannelCard("1", findViewById(R.id.channel1)),
            "2" to ChannelCard("2", findViewById(R.id.channel2)),
        )
        linkHeaderFocus()

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
                launch {
                    // Never sit on "Updating…" forever if Android doesn't report back.
                    Updater.state.collectLatest { state ->
                        if (state is UpdateState.Installing) {
                            delay(INSTALL_TIMEOUT_MS)
                            Updater.onInstallTimedOut(state)
                        }
                    }
                }
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
        resetIdleTimer()
    }

    override fun onStop() {
        exitDarkMode()
        idleJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // In dark mode, any button (other than volume and media keys) just wakes the screen.
        if (darkMode && code !in PASS_THROUGH_KEYS) {
            if (event.action == KeyEvent.ACTION_UP) exitDarkMode()
            return true
        }
        if (settingsOverlay.isVisible && code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) closeSettings()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) resetIdleTimer()
        return super.dispatchKeyEvent(event)
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

    private fun isPlayingNow(): Boolean = controller?.playWhenReady == true

    // ---- Settings and dark mode ----

    private fun setUpSettingsAndDarkMode() {
        settings = AppSettings(this)
        content = findViewById(R.id.content)
        settingsButton = findViewById(R.id.settings_button)
        settingsOverlay = findViewById(R.id.settings_overlay)
        volumeRow = findViewById(R.id.volume_row)
        volumeValue = findViewById(R.id.volume_value)
        volumeBar = findViewById(R.id.volume_bar)
        volumeHint = findViewById(R.id.volume_hint)
        darkValue = findViewById(R.id.dark_value)
        darkOverlay = findViewById(R.id.dark_overlay)
        darkMark = findViewById(R.id.dark_mark)
        darkDot = findViewById(R.id.dark_dot)

        settingsButton.setOnClickListener { openSettings() }
        findViewById<View>(R.id.dark_button).setOnClickListener { enterDarkMode() }
        findViewById<TextView>(R.id.settings_footer).text =
            getString(R.string.settings_footer, BuildConfig.VERSION_NAME)

        val gap = dp(3)
        repeat(AppSettings.VOLUME_DB.size) {
            volumeBar.addView(
                View(this),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = gap
                    marginEnd = gap
                },
            )
        }

        volumeRow.setOnKeyListener { _, keyCode, event ->
            stepperKey(keyCode, event) { delta ->
                settings.volumeStep += delta
                renderSettings()
            }
        }
        val darkRow = findViewById<View>(R.id.dark_row)
        darkRow.setOnKeyListener { _, keyCode, event ->
            stepperKey(keyCode, event) { delta -> changeDarkAfter(delta) }
        }
        darkRow.setOnClickListener { changeDarkAfter(1, wrap = true) }
    }

    /**
     * The header buttons sit far from the cards, so automatic D-pad navigation doesn't connect
     * them reliably: wire up/down between them explicitly.
     */
    private fun linkHeaderFocus() {
        val card1 = cards.getValue("1").root
        val card2 = cards.getValue("2").root
        card1.nextFocusUpId = R.id.dark_button
        card2.nextFocusUpId = R.id.settings_button
        card2.nextFocusRightId = R.id.channel2 // don't jump down into the mixtapes row
        findViewById<View>(R.id.dark_button).nextFocusDownId = R.id.channel2
        findViewById<View>(R.id.settings_button).apply {
            nextFocusDownId = R.id.channel2
            nextFocusRightId = R.id.settings_button // nothing to its right; don't drop into the tiles
        }
        updateButton.nextFocusDownId = R.id.channel1
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            val name = now?.id?.takeIf { it != View.NO_ID }?.let {
                try {
                    resources.getResourceEntryName(it)
                } catch (e: Exception) {
                    null
                }
            }
            if (name != null) Log.d(FOCUS_TAG, "Focus: $name")
        }
    }

    /** Left/right on a settings row changes its value. */
    private fun stepperKey(keyCode: Int, event: KeyEvent, change: (Int) -> Unit): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) change(delta)
        return true
    }

    private fun changeDarkAfter(delta: Int, wrap: Boolean = false) {
        val count = AppSettings.DARK_AFTER_MINUTES.size
        val next = settings.darkAfterStep + delta
        settings.darkAfterStep = if (wrap) Math.floorMod(next, count) else next
        renderSettings()
        resetIdleTimer()
        updateKeepScreenOn()
    }

    private fun openSettings() {
        renderSettings()
        settingsOverlay.isVisible = true
        volumeRow.requestFocus()
        // Keep the remote's focus inside the panel while it's open.
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun closeSettings() {
        settingsOverlay.isVisible = false
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        settingsButton.requestFocus()
    }

    private fun renderSettings() {
        val db = settings.volumeDb
        volumeValue.text = when {
            db > 0 -> getString(R.string.volume_louder, db)
            db < 0 -> getString(R.string.volume_quieter, -db)
            else -> getString(R.string.volume_normal)
        }
        val normal = AppSettings.DEFAULT_VOLUME_STEP
        for (i in 0 until volumeBar.childCount) {
            val color = when {
                i > settings.volumeStep -> 0x33FFFFFF
                i > normal -> ContextCompat.getColor(this, R.color.accent)
                else -> 0xFFFFFFFF.toInt()
            }
            volumeBar.getChildAt(i).setBackgroundColor(color)
        }
        volumeHint.setText(
            if (PlaybackService.boostAvailable == false) R.string.volume_hint_no_boost else R.string.volume_hint,
        )
        val minutes = settings.darkAfterMinutes
        darkValue.text = if (minutes == 0) {
            getString(R.string.dark_after_off)
        } else {
            getString(R.string.dark_after_minutes, minutes)
        }
    }

    private fun enterDarkMode() {
        if (darkMode) return
        darkMode = true
        idleJob?.cancel()
        if (settingsOverlay.isVisible) closeSettings()
        darkOverlay.alpha = 0f
        darkOverlay.isVisible = true
        darkOverlay.animate().alpha(1f).setDuration(600).start()
        darkOverlay.post { moveDarkMark(animate = false) }
        // Move the mark now and then so nothing stays lit in one place (OLED burn-in).
        driftJob = lifecycleScope.launch {
            while (true) {
                delay(DRIFT_INTERVAL_MS)
                moveDarkMark(animate = true)
            }
        }
        Log.i(SCREEN_TAG, "Dark screen on")
        render()
    }

    private fun exitDarkMode() {
        if (!darkMode) return
        darkMode = false
        driftJob?.cancel()
        darkOverlay.animate().cancel()
        darkOverlay.isVisible = false
        Log.i(SCREEN_TAG, "Dark screen off")
        updateKeepScreenOn()
        resetIdleTimer()
    }

    private fun moveDarkMark(animate: Boolean) {
        val margin = dp(48)
        val maxX = (darkOverlay.width - darkMark.width - 2 * margin).coerceAtLeast(0)
        val maxY = (darkOverlay.height - darkMark.height - 2 * margin).coerceAtLeast(0)
        val x = (margin + Random.nextInt(maxX + 1)).toFloat()
        val y = (margin + Random.nextInt(maxY + 1)).toFloat()
        if (!animate) {
            darkMark.translationX = x
            darkMark.translationY = y
            return
        }
        val shown = darkMark.alpha
        darkMark.animate().alpha(0f).setDuration(1_000).withEndAction {
            darkMark.translationX = x
            darkMark.translationY = y
            darkMark.animate().alpha(shown).setDuration(1_000).start()
        }.start()
    }

    /** Goes dark after a few minutes without button presses, while something is playing. */
    private fun resetIdleTimer() {
        idleJob?.cancel()
        val minutes = settings.darkAfterMinutes
        if (darkMode || minutes <= 0) return
        idleJob = lifecycleScope.launch {
            delay(minutes * 60_000L)
            if (isPlayingNow()) enterDarkMode()
        }
    }

    /**
     * While playing, keeps the TV's own screensaver from taking over: the app's dark screen
     * replaces it. When nothing is playing, the TV behaves normally.
     */
    private fun updateKeepScreenOn() {
        val keepOn = isPlayingNow() && (darkMode || settings.darkAfterMinutes > 0)
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        val state = Updater.state.value
        val waiting = state is UpdateState.Ready || state is UpdateState.NeedsPermission
        if (connected != null && !connected.playWhenReady && waiting) {
            Updater.install(applicationContext, update)
        }
    }

    private fun installUpdateFromButton() {
        val update = when (val state = Updater.state.value) {
            is UpdateState.Ready -> state.update
            is UpdateState.Failed -> state.update
            is UpdateState.NeedsPermission -> {
                if (!Updater.canInstall(this)) {
                    openInstallPermissionSettings()
                    return
                }
                state.update
            }
            else -> return
        }
        lifecycleScope.launch { Updater.install(applicationContext, update) }
    }

    /** The one-off "allow NTS Radio to install apps" switch, as directly as this TV allows. */
    private fun openInstallPermissionSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                Log.i(Updater.TAG, "Opened settings: ${intent.action}")
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next, more general screen.
            }
        }
    }

    private fun renderUpdate(state: UpdateState) {
        if (state is UpdateState.NeedsConfirmation) {
            // Show Android's own confirmation from this visible screen (it can't be blocked here).
            try {
                startActivity(state.prompt)
                Updater.onPromptShown()
            } catch (e: Exception) {
                Log.w(Updater.TAG, "Couldn't show the install prompt: ${e.message}")
                Updater.onInstallFailed()
            }
            return
        }
        val (text, actionable) = when (state) {
            UpdateState.None -> null to false
            is UpdateState.Ready -> getString(R.string.update_ready, state.update.version) to true
            is UpdateState.NeedsPermission -> getString(R.string.update_needs_permission) to true
            is UpdateState.Installing -> getString(R.string.update_installing, state.update.version) to false
            is UpdateState.Failed -> getString(R.string.update_failed, state.update.version) to true
            is UpdateState.NeedsConfirmation -> null to false
        }
        val wasVisible = updateButton.isVisible
        updateButton.isVisible = text != null
        updateButton.text = text
        updateButton.isFocusable = actionable
        updateButton.isClickable = actionable
        // Make the one-off permission step easy to find with the remote.
        if (state is UpdateState.NeedsPermission && !wasVisible && !darkMode && !settingsOverlay.isVisible) {
            updateButton.requestFocus()
        }
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
            nowPlaying.isInvisible = false
        } else {
            nowPlaying.isInvisible = true
        }

        val playing = isPlayingNow()
        if (playing != wasPlaying) {
            wasPlaying = playing
            resetIdleTimer()
        }
        updateKeepScreenOn()
        darkDot.alpha = if (playing) 1f else 0.3f

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

private const val SCREEN_TAG = "NtsTvScreen"
private const val FOCUS_TAG = "NtsTvFocus"
private const val INSTALL_TIMEOUT_MS = 3 * 60_000L
private const val DRIFT_INTERVAL_MS = 60_000L

/** Keys that keep working normally while the screen is dark. */
private val PASS_THROUGH_KEYS = setOf(
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_MUTE,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_STOP,
    KeyEvent.KEYCODE_HEADSETHOOK,
)

internal fun View.animateFocus(focused: Boolean, scale: Float) {
    val target = if (focused) scale else 1f
    animate().scaleX(target).scaleY(target).setDuration(150).start()
}
