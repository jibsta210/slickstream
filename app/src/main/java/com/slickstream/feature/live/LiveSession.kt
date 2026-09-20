package com.slickstream.feature.live

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.slickstream.core.diagnostics.Diagnostics
import com.slickstream.feature.live.LiveRecoveryPlan.LiveFailureKind
import com.slickstream.feature.live.LiveRecoveryPlan.LiveFeedHealth
import com.slickstream.feature.live.LiveRecoveryPlan.LiveFeedInfo
import com.slickstream.feature.live.LiveRecoveryPlan.LiveRecoveryAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ONE live game — its ExoPlayer, its stall watchdog, and its full recovery ladder — as a
 * self-contained unit so several can run side by side in multiview.
 *
 * This is the retry engine that shipped in [LivePlayerViewModel], lifted out verbatim behind an
 * `id` and a handful of control setters ([setAudible], [setVideoEnabled], [setVideoCap]). Nothing
 * about the recovery behaviour changed; it simply no longer assumes it is the only player alive.
 * Two things made that lift necessary rather than cosmetic:
 *
 *  - EACH TILE IS ITS OWN HARDWARE DECODER. A TV box has a small fixed number of them, so a tile
 *    that is not currently drawn ([setVideoEnabled] false, e.g. while another tile is expanded to
 *    full screen) must DISABLE its video track to hand the codec back — not merely hide a view.
 *  - EXACTLY ONE TILE IS AUDIBLE. Four soundtracks at once is noise, so [setAudible] gates volume.
 *
 * The [scope] is owned by the coordinator and is a per-session child job: one tile's failure or
 * release must never cancel its neighbours. [release] tears down the player; the scope is cancelled
 * by the coordinator.
 */
@OptIn(UnstableApi::class)
class LiveSession(
    val id: Int,
    val title: String,
    val feeds: List<LivePlaybackHolder.Feed>,
    startIndex: Int,
    private val appContext: Context,
    private val resolver: WebViewStreamResolver,
    private val diagnostics: Diagnostics,
    private val audioCode: () -> String,
    private val scope: CoroutineScope,
) {

    sealed interface UiState {
        data object Buffering : UiState
        data object Playing : UiState
        data class Recovering(val message: String) : UiState
        data class Error(val message: String) : UiState
        data object NoStream : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Buffering)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _currentIndex = MutableStateFlow(startIndex.coerceIn(0, (feeds.size - 1).coerceAtLeast(0)))
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val feedInfos: List<LiveFeedInfo> = feeds.mapIndexed { i, f -> LiveFeedInfo(i, f.needsResolution) }

    private var playJob: Job? = null
    private var watchdogJob: Job? = null
    private var recoveryJob: Job? = null

    private val monitor = LiveStallMonitor()
    private var generation = 0
    private var resolvedUrl: String? = null
    private var attempt = 0
    private var autoSwitchesSinceHealthy = 0
    private val feedHealth = mutableMapOf<Int, LiveFeedHealth>()
    private var hasReachedPlaying = false

    // --- Multiview control surface ---------------------------------------------------------------
    // Applied at build time and re-applied live whenever the coordinator changes the layout.

    private var audible = false
    private var videoEnabled = true
    private var videoCap: LiveMultiView.VideoCap? = null

    fun start() {
        if (feeds.isEmpty()) {
            _uiState.value = UiState.NoStream
            return
        }
        play(_currentIndex.value)
    }

    /** Only one tile is heard at a time; the rest are muted at the player, not merely lowered. */
    fun setAudible(value: Boolean) {
        if (audible == value) return
        audible = value
        _player.value?.let { runCatching { it.volume = if (value) 1f else 0f } }
    }

    /**
     * Hand the hardware decoder back when this tile is not on screen (another tile is expanded).
     * Disabling the video TRACK releases the codec; re-enabling re-inits it — a ~1s cost paid only
     * when collapsing back to the grid, versus a cold player rebuild.
     */
    fun setVideoEnabled(value: Boolean) {
        if (videoEnabled == value) return
        videoEnabled = value
        _player.value?.let { applyTrackParams(it) }
    }

    /** Cap the decoded rendition to the size the tile is actually drawn at (grid/PiP), or null for full. */
    fun setVideoCap(cap: LiveMultiView.VideoCap?) {
        if (videoCap == cap) return
        videoCap = cap
        _player.value?.let { applyTrackParams(it) }
    }

    fun switchTo(index: Int) = switchTo(index, auto = false)

    private fun switchTo(index: Int, auto: Boolean) {
        if (index !in feeds.indices) return
        if (auto) {
            autoSwitchesSinceHealthy++
        } else {
            autoSwitchesSinceHealthy = 0
            feedHealth[index] = (feedHealth[index] ?: LiveFeedHealth()).copy(lastFailedAtMs = 0L, consecutiveFailures = 0)
        }
        diagnostics.breadcrumb("live.switch id=$id idx=$index auto=$auto")
        _currentIndex.value = index
        play(index)
    }

    private fun play(index: Int) {
        val feed = feeds.getOrNull(index) ?: return
        val gen = ++generation
        playJob?.cancel()
        recoveryJob?.cancel()
        watchdogJob?.cancel()
        _player.value?.release()
        _player.value = null
        resolvedUrl = null
        attempt = 0
        hasReachedPlaying = false
        monitor.reset()
        _uiState.value = UiState.Buffering
        playJob = scope.launch {
            runCatching {
                if (feed.needsResolution && !resolver.isWebViewAvailable()) {
                    _uiState.value = UiState.Error(
                        "Live sports need Android System WebView, which isn't available on this device. " +
                            "Install/enable it from the Play Store, then try again.",
                    )
                    return@launch
                }
                val playUrl = if (feed.needsResolution) {
                    resolver.resolve(
                        embedUrl = feed.url,
                        pageReferer = "https://streamed.pk/",
                        userAgent = feed.headers["User-Agent"] ?: DEFAULT_UA,
                    )
                } else {
                    feed.url
                }
                ensureActive()
                if (gen != generation) return@launch
                if (playUrl.isNullOrBlank()) leaveDeadFeed("no playable url found")
                else buildPlayer(playUrl, feed.headers)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                if (gen == generation) leaveDeadFeed("resolve threw: ${it.javaClass.simpleName}")
            }
        }
    }

    private fun buildPlayer(url: String, headers: Map<String, String>) {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers["User-Agent"] ?: DEFAULT_UA)
            .setConnectTimeoutMs(LIVE_HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(LIVE_HTTP_TIMEOUT_MS)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
            .setBackBuffer(0, false)
            .build()

        val renderers = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setAllowedVideoJoiningTimeMs(0L)

        val exo = ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy()),
            )
            .build()
            .apply {
                setMediaItem(liveMediaItem(url))
                volume = if (audible) 1f else 0f
                addListener(playerListener())
                prepare()
                playWhenReady = true
            }
        applyTrackParams(exo)
        resolvedUrl = url
        monitor.reset()
        _player.value = exo
        startWatchdog()
    }

    /** Preferred audio language + multiview video cap + video-track enable, in one place. */
    private fun applyTrackParams(exo: ExoPlayer) {
        runCatching {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(audioCode())
                .apply {
                    val cap = videoCap
                    if (cap != null) setMaxVideoSize(cap.maxWidth, cap.maxHeight) else clearVideoSizeConstraints()
                    setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled)
                }
                .build()
        }
    }

    private fun liveMediaItem(url: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(20_000L)
                .setMinOffsetMs(8_000L)
                .setMaxOffsetMs(45_000L)
                .setMinPlaybackSpeed(0.97f)
                .setMaxPlaybackSpeed(1.05f)
                .build(),
        )
        .build()

    private fun playerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    hasReachedPlaying = true
                    _uiState.value = UiState.Playing
                }
                Player.STATE_BUFFERING ->
                    if (_uiState.value !is UiState.Playing && _uiState.value !is UiState.Recovering) {
                        _uiState.value = UiState.Buffering
                    }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val kind = classify(error)
            diagnostics.breadcrumb("live.error id=$id code=${error.errorCode} kind=$kind attempt=$attempt")
            recover(kind, bufferAdvancing = false)
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        val gen = generation
        watchdogJob = scope.launch {
            while (isActive) {
                delay(LiveRecoveryPlan.WATCHDOG_TICK_MS)
                if (gen != generation) return@launch
                val p = _player.value ?: continue
                val now = android.os.SystemClock.elapsedRealtime()
                val verdict = runCatching {
                    monitor.sample(
                        nowMs = now,
                        positionMs = p.currentPosition,
                        bufferedPositionMs = p.bufferedPosition,
                        playWhenReady = p.playWhenReady,
                        isPlaying = p.isPlaying,
                        playbackState = p.playbackState,
                    )
                }.getOrNull() ?: continue
                when (verdict) {
                    is LiveStallMonitor.Verdict.Healthy -> onHealthyTick(verdict.playingForMs)
                    is LiveStallMonitor.Verdict.Stalled -> recover(verdict.kind, verdict.bufferAdvancing)
                    LiveStallMonitor.Verdict.Settling -> Unit
                }
            }
        }
    }

    private fun onHealthyTick(playingForMs: Long) {
        hasReachedPlaying = true
        if (_uiState.value !is UiState.Playing) _uiState.value = UiState.Playing
        val idx = _currentIndex.value
        val health = feedHealth[idx] ?: LiveFeedHealth()
        feedHealth[idx] = health.copy(totalPlayedMs = health.totalPlayedMs + LiveRecoveryPlan.WATCHDOG_TICK_MS)

        if (attempt != 0 && LiveRecoveryPlan.recoveryConfirmed(playingForMs)) {
            diagnostics.breadcrumb("live.recover id=$id rung held -> ladder reset")
            attempt = 0
        }
        if (LiveRecoveryPlan.shouldResetBudget(playingForMs) &&
            (attempt != 0 || autoSwitchesSinceHealthy != 0 || feedHealth[idx]?.consecutiveFailures != 0)
        ) {
            attempt = 0
            autoSwitchesSinceHealthy = 0
            feedHealth[idx] = feedHealth[idx]!!.copy(consecutiveFailures = 0)
        }
    }

    private fun recover(kind: LiveFailureKind, bufferAdvancing: Boolean) {
        if (recoveryJob?.isActive == true) return
        val gen = generation
        val idx = _currentIndex.value
        val step = LiveRecoveryPlan.decide(
            LiveRecoveryPlan.Input(
                kind = kind,
                attempt = attempt,
                bufferAdvancing = bufferAdvancing,
                currentFeedIndex = idx,
                feeds = feedInfos,
                health = feedHealth,
                nowMs = android.os.SystemClock.elapsedRealtime(),
                autoSwitchesSinceHealthy = autoSwitchesSinceHealthy,
            ),
        )
        diagnostics.breadcrumb("live.recover id=$id kind=$kind attempt=$attempt -> ${step.action}")
        attempt++
        val p = _player.value
        recoveryJob = scope.launch {
            if (step.action != LiveRecoveryAction.GIVE_UP) {
                _uiState.value = UiState.Recovering(labelFor(step.action))
            }
            if (step.delayMs > 0) {
                delay(step.delayMs)
                if (_uiState.value is UiState.Playing) {
                    attempt = (attempt - 1).coerceAtLeast(0)
                    return@launch
                }
            }
            if (gen != generation) return@launch
            when (step.action) {
                LiveRecoveryAction.SEEK_TO_LIVE_EDGE -> {
                    if (p != null && _player.value === p) {
                        runCatching { p.seekToDefaultPosition(); p.playWhenReady = true }
                    }
                    monitor.onRecoveryIssued(android.os.SystemClock.elapsedRealtime(), SEEK_SETTLE_MS)
                }
                LiveRecoveryAction.REPREPARE -> reprepare(p, gen)
                LiveRecoveryAction.REBUILD -> rebuild(gen)
                LiveRecoveryAction.SWITCH_FEED -> {
                    markFeedFailed(idx)
                    switchTo(step.targetFeedIndex, auto = true)
                }
                LiveRecoveryAction.GIVE_UP -> {
                    markFeedFailed(idx)
                    watchdogJob?.cancel()
                    _uiState.value = UiState.Error(
                        if (hasReachedPlaying) "This feed keeps dropping and we couldn't get it back. Try another source."
                        else "This feed didn't load. Try another source.",
                    )
                }
            }
        }
    }

    private fun reprepare(p: ExoPlayer?, gen: Int) {
        val url = resolvedUrl
        if (p == null || url == null || _player.value !== p || gen != generation) return
        runCatching {
            p.stop()
            p.setMediaItem(liveMediaItem(url))
            p.prepare()
            p.playWhenReady = true
        }
        monitor.onRecoveryIssued(android.os.SystemClock.elapsedRealtime(), PREPARE_SETTLE_MS)
    }

    private suspend fun rebuild(gen: Int) {
        val idx = _currentIndex.value
        val feed = feeds.getOrNull(idx) ?: return
        val url = if (feed.needsResolution) {
            if (!resolver.isWebViewAvailable()) null
            else resolver.resolve(
                embedUrl = feed.url,
                pageReferer = "https://streamed.pk/",
                userAgent = feed.headers["User-Agent"] ?: DEFAULT_UA,
                timeoutMs = RE_RESOLVE_TIMEOUT_MS,
            )
        } else {
            feed.url
        }
        if (gen != generation) return
        if (url.isNullOrBlank()) { leaveDeadFeed("re-resolve found no url"); return }
        val p = _player.value
        if (p != null) {
            resolvedUrl = url
            runCatching {
                p.stop()
                p.setMediaItem(liveMediaItem(url))
                p.prepare()
                p.playWhenReady = true
            }
            monitor.onRecoveryIssued(android.os.SystemClock.elapsedRealtime(), PREPARE_SETTLE_MS)
        } else {
            buildPlayer(url, feed.headers)
        }
    }

    private fun leaveDeadFeed(why: String) {
        val idx = _currentIndex.value
        markFeedFailed(idx)
        val target = if (autoSwitchesSinceHealthy < LiveRecoveryPlan.maxAutoSwitches(feeds.size)) {
            LiveRecoveryPlan.chooseFeed(idx, feedInfos, feedHealth, android.os.SystemClock.elapsedRealtime())
        } else {
            null
        }
        if (target != null) switchTo(target, auto = true)
        else _uiState.value = UiState.Error("Couldn't find a playable stream for this feed. Try another source.")
    }

    private fun markFeedFailed(index: Int) {
        val h = feedHealth[index] ?: LiveFeedHealth()
        feedHealth[index] = h.copy(
            lastFailedAtMs = android.os.SystemClock.elapsedRealtime(),
            consecutiveFailures = h.consecutiveFailures + 1,
        )
    }

    private fun labelFor(action: LiveRecoveryAction): String = when (action) {
        LiveRecoveryAction.SEEK_TO_LIVE_EDGE -> "Rejoining live…"
        LiveRecoveryAction.REPREPARE -> "Reconnecting…"
        LiveRecoveryAction.REBUILD -> "Refreshing the stream…"
        LiveRecoveryAction.SWITCH_FEED -> "Switching feed…"
        LiveRecoveryAction.GIVE_UP -> ""
    }

    private fun classify(error: PlaybackException): LiveFailureKind {
        val http = httpStatusOf(error)
        return when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> LiveFailureKind.BEHIND_LIVE_WINDOW
            http in STALE_TOKEN_STATUSES -> LiveFailureKind.STALE_URL
            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> LiveFailureKind.TRANSIENT_IO
            error.errorCode in 2000..2999 -> LiveFailureKind.TRANSIENT_IO
            else -> LiveFailureKind.FATAL
        }
    }

    private fun httpStatusOf(error: PlaybackException): Int? {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < CAUSE_SCAN_DEPTH) {
            (cause as? HttpDataSource.InvalidResponseCodeException)?.let { return it.responseCode }
            cause = cause.cause
            depth++
        }
        return null
    }

    /** Manual retry from the tile's error overlay: a clean restart of this feed, budget reset. */
    fun retry() = switchTo(_currentIndex.value, auto = false)

    fun release() {
        generation++
        watchdogJob?.cancel()
        recoveryJob?.cancel()
        playJob?.cancel()
        _player.value?.release()
        _player.value = null
    }

    private companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
        const val LIVE_HTTP_TIMEOUT_MS = 4_000
        const val RE_RESOLVE_TIMEOUT_MS = 12_000L
        const val SEEK_SETTLE_MS = 4_000L
        const val PREPARE_SETTLE_MS = 6_000L
        val STALE_TOKEN_STATUSES = setOf(401, 403, 410)
        const val CAUSE_SCAN_DEPTH = 6
    }
}
