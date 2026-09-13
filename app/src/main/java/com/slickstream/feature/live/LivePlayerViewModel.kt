package com.slickstream.feature.live

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.slickstream.feature.live.LiveRecoveryPlan.LiveFailureKind
import com.slickstream.feature.live.LiveRecoveryPlan.LiveFeedHealth
import com.slickstream.feature.live.LiveRecoveryPlan.LiveFeedInfo
import com.slickstream.feature.live.LiveRecoveryPlan.LiveRecoveryAction
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A lightweight HLS player for live sports — separate from the torrent
 * [com.slickstream.feature.player.PlayerViewModel]. Reads the selected feed from
 * [LivePlaybackHolder] and plays it via ExoPlayer with the host's required request headers.
 *
 * If the feed is an obfuscated embed (streamed.pk, `needsResolution`), it's first run through
 * [WebViewStreamResolver] to capture the real `.m3u8`; FanCode direct feeds play immediately.
 * Because resolution is async, [player] is a StateFlow set once the stream is ready.
 *
 * THE RETRY ENGINE — "NFL day: streams freeze every few minutes, I switch fox 1 / fox 2 by hand,
 * they come back, then freeze again". Three layers, and the user should never touch the remote for
 * any of them:
 *
 *  1. DETECT. [LiveStallMonitor] polls the player once a second and reports a freeze from
 *     `currentPosition` rather than from `onPlaybackStateChanged`. The old code could not see this
 *     class of failure at all — it only set `Buffering` when it was NOT already `Playing`, making
 *     `Playing` an absorbing state, and the dominant live freeze (a stuck HLS playlist) never
 *     produces a state change in the first place.
 *  2. ABSORB. [LiveLoadErrorHandlingPolicy] widens the loader's own retry budget from the stock
 *     3-attempts-in-~3-seconds so ordinary segment blips never surface as errors at all.
 *  3. ESCALATE. [LiveRecoveryPlan] picks the cheapest repair that can possibly work — seek to the
 *     live edge, then re-prepare in place, then re-resolve, then another feed — with a budget that
 *     RESETS after [LiveRecoveryPlan.RECOVERY_RESET_MS] of clean playback, so a feed that misbehaves
 *     every few minutes is rescued for the whole three hours of a game.
 *
 * Everything here is live-specific in one respect that matters: recovery means REJOINING THE LIVE
 * EDGE, never resuming an old position. Every repair either seeks to the default (live) position or
 * re-sets the media item, which `ExoPlayerImpl.setMediaItem(MediaItem)` is verified to do with
 * `resetPosition = true`.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val holder: LivePlaybackHolder,
    private val resolver: WebViewStreamResolver,
    private val diagnostics: com.slickstream.core.diagnostics.Diagnostics,
    private val settingsRepository: com.slickstream.data.settings.SettingsRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Buffering : UiState
        data object Playing : UiState

        /**
         * Playing-but-broken, and being actively repaired. A DISTINCT state from [Buffering] on
         * purpose: the last decoded frame is still on screen, so this renders as a small
         * "Reconnecting…" badge over the picture rather than a full-screen spinner, and it is NOT the
         * 3-button [Error] overlay — an overlay for a two-second hiccup is what made the user reach
         * for the remote in the first place.
         */
        data class Recovering(val message: String) : UiState
        data class Error(val message: String) : UiState
        data object NoStream : UiState
    }

    /** Preferred spoken language, one source of truth with the main player (AppSettings). */
    private val audioLanguage: StateFlow<String> = settingsRepository.settings
        .map { it.audioLanguage.code }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            com.slickstream.data.settings.AudioLanguage.DEFAULT.code,
        )
    private val preferredAudioCode: String get() = audioLanguage.value

    private val _uiState = MutableStateFlow<UiState>(UiState.Buffering)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    val title: String = holder.current?.title ?: "Live"

    /** All feeds for the event — labels for the in-player stream switcher. */
    val feeds: List<LivePlaybackHolder.Feed> = holder.current?.feeds ?: emptyList()

    private val _currentIndex = MutableStateFlow(holder.current?.index ?: 0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val feedInfos: List<LiveFeedInfo> =
        feeds.mapIndexed { i, f -> LiveFeedInfo(i, f.needsResolution) }

    private var playJob: kotlinx.coroutines.Job? = null
    private var watchdogJob: kotlinx.coroutines.Job? = null
    private var recoveryJob: kotlinx.coroutines.Job? = null

    private val monitor = LiveStallMonitor()

    /**
     * Bumped by every [play] and every feed switch. Captured by each async step and re-checked after
     * every suspension point. Coroutine cancellation is COOPERATIVE: a resolve job already past its
     * last suspension point can still run to completion and overwrite [_player] with a player for the
     * feed the user just left — leaking an ExoPlayer (and with it a MediaCodec instance, a hard and
     * small budget on a TV box) and binding the wrong stream. The retry engine calls these paths far
     * more often than the old code did, so the race is no longer theoretical.
     */
    private var generation = 0

    /** The URL actually handed to ExoPlayer, so a cheap in-place retry has something to re-prepare. */
    private var resolvedUrl: String? = null

    /** Attempts already made in the CURRENT stall episode. Zeroed by sustained clean playback. */
    private var attempt = 0

    /** Automatic feed switches since the last sustained clean run — the anti-rotation budget. */
    private var autoSwitchesSinceHealthy = 0

    private val feedHealth = mutableMapOf<Int, LiveFeedHealth>()

    /** True once this feed has actually played, which is what separates "blip" from "dead link". */
    private var hasReachedPlaying = false

    init {
        diagnostics.breadcrumb("live.vm init title='$title' feeds=${feeds.size} hasSelection=${holder.current != null}")
        if (holder.current == null) _uiState.value = UiState.NoStream
        else play(_currentIndex.value)
    }

    /**
     * Switch to another feed for the same event without leaving the player.
     *
     * Re-selecting the CURRENT feed is allowed and means "restart this one". The old guard
     * (`if (index == _currentIndex.value ... ) return`) made the most natural move from the error
     * overlay — open "Other streams" and re-pick the feed that was working a minute ago, the row
     * marked as selected — a silent no-op: the panel closed and the error just sat there.
     */
    fun switchTo(index: Int) = switchTo(index, auto = false)

    private fun switchTo(index: Int, auto: Boolean) {
        if (index !in feeds.indices) return
        if (auto) {
            autoSwitchesSinceHealthy++
        } else {
            // The user's own choice is authoritative and wipes the automatic budget: they have told us
            // this feed is worth a fresh start, so it gets the full ladder again.
            autoSwitchesSinceHealthy = 0
            feedHealth[index] = (feedHealth[index] ?: LiveFeedHealth()).copy(
                lastFailedAtMs = 0L,
                consecutiveFailures = 0,
            )
        }
        diagnostics.breadcrumb("live.switch idx=$index auto=$auto autoSwitches=$autoSwitchesSinceHealthy")
        _currentIndex.value = index
        play(index)
    }

    private fun play(index: Int) {
        val feed = feeds.getOrNull(index) ?: return
        diagnostics.breadcrumb("live.play idx=$index needsResolution=${feed.needsResolution}")
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
        playJob = viewModelScope.launch {
            // Whole path guarded: a resolver/WebView/ExoPlayer failure must surface as an in-player
            // error (with a "try another source" affordance), never crash the app.
            runCatching {
                // No WebView provider (common on Android TV boxes) — every embed source needs it, so
                // say so plainly instead of spinning through "try another source" on each one.
                diagnostics.breadcrumb("live.play checking webview availability")
                if (feed.needsResolution && !resolver.isWebViewAvailable()) {
                    diagnostics.breadcrumb("live.play webview UNAVAILABLE -> showing message")
                    _uiState.value = UiState.Error(
                        "Live sports need Android System WebView, which isn't available on this device. " +
                            "Install/enable it from the Play Store, then try again.",
                    )
                    return@launch
                }
                val playUrl = if (feed.needsResolution) {
                    // Resolve the embed -> m3u8 invisibly. The embed's referrer check wants its PARENT
                    // site (streamed.pk); the resulting m3u8 plays with the embed.st headers.
                    diagnostics.breadcrumb("live.play resolving embed via webview")
                    resolver.resolve(
                        embedUrl = feed.url,
                        pageReferer = "https://streamed.pk/",
                        userAgent = feed.headers["User-Agent"] ?: DEFAULT_UA,
                    )
                } else {
                    feed.url
                }
                // The resolve above can take up to its 40 s timeout; a user switch during it must win.
                ensureActive()
                if (gen != generation) return@launch
                if (playUrl.isNullOrBlank()) {
                    // A feed we cannot even resolve is a dead feed, not a stalled one — no ladder rung
                    // applies. Hand it straight to the feed chooser so a single dead embed doesn't
                    // strand the user on an overlay while a working feed sits one row away.
                    leaveDeadFeed("no playable url found")
                } else {
                    buildPlayer(playUrl, feed.headers)
                }
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
            // Half the stock DEFAULT_CONNECT/READ_TIMEOUT_MILLIS (both 8000, verified). A dead socket
            // otherwise burns 8 s before it is even an error — longer than a segment — and the extra
            // retries LiveLoadErrorHandlingPolicy grants would not fit inside a segment duration.
            .setConnectTimeoutMs(LIVE_HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(LIVE_HTTP_TIMEOUT_MS)

        // Stock DefaultLoadControl is tuned for VOD and actively MANUFACTURES the live failure it is
        // supposed to prevent. Verified constants: DEFAULT_MIN_BUFFER_MS = 50_000 is unsatisfiable on
        // a live window that often holds only ~30 s of media, and DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_
        // REBUFFER_MS = 5_000 means the player refuses to resume after EVERY stall until it has
        // re-accumulated 5 seconds — 5 seconds taken out of the live window, so each stall walks the
        // playhead further back until it falls off the end as ERROR_CODE_BEHIND_LIVE_WINDOW. The two
        // symptoms in the user's report are one feedback loop, and this is where it is cut.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs — reachable inside a real live window
                30_000, // maxBufferMs — a live feed cannot buffer past the live edge anyway
                1_500,  // bufferForPlaybackMs — first frame just as fast as before
                3_000,  // bufferForPlaybackAfterRebufferMs — was 5_000; halving it halves the drift per stall
            )
            // Nobody scrubs backwards in a live feed; on a 1.5 GB box the back buffer is pure waste.
            .setBackBuffer(0, false)
            .build()

        // The two renderer fixes the VOD players already carry, which the live path never received.
        val renderers = DefaultRenderersFactory(appContext)
            // Without this a single HEVC/10-bit decoder-init failure on a weak TV box is an instant
            // hard ERROR_CODE_DECODER_INIT_FAILED with no software-decoder second chance.
            .setEnableDecoderFallback(true)
            // DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS = 5000 is the deliberate first-frame suppression
            // removed from the VOD players: while the joining deadline is armed,
            // VideoFrameReleaseControl.isReady() returns true on the DEADLINE ALONE (so the player
            // claims READY over a black picture) and shouldForceRelease() withholds the one free frame
            // a surface change grants. LivePlayerScreen already calls RebindVideoSurfaceOnResume for
            // this player; zeroing this is what lets that rebind's frame actually reach the screen.
            .setAllowedVideoJoiningTimeMs(0L)

        val exo = ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy()),
            )
            .build()
            .apply {
                // Same preferred-SPOKEN-language rule as the main player. International IPTV/HLS feeds
                // very often carry several audio renditions, and with no preference set ExoPlayer falls
                // through to the DEFAULT-flag / channel-count tie-break — the identical
                // silent-wrong-language outcome, on a surface with no picker to escape it.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(preferredAudioCode)
                    .build()
                setMediaItem(liveMediaItem(url))
                addListener(playerListener())
                prepare()
                playWhenReady = true
            }
        resolvedUrl = url
        monitor.reset()
        _player.value = exo
        startWatchdog()
    }

    /**
     * A MediaItem with an EXPLICIT live configuration, and a deliberately LARGE target offset.
     *
     * Two verified facts drive this. First, `HlsMediaSource.updateLiveConfiguration` sets
     * `useDefaultPlaybackSpeed = true` — pinning min = max = 1.0f, i.e. disabling live speed control
     * entirely — when the MediaItem specifies neither speed AND the playlist carries no
     * EXT-X-SERVER-CONTROL hold-back. Scraped sports playlists essentially never carry one, so until
     * now the speed control on these feeds was completely inert and the playhead could only ever drift
     * further behind. Second, `DefaultLoadControl.shouldStartPlayback` does
     * `minBufferDurationUs = min(targetLiveOffsetUs / 2, minBufferDurationUs)` — so a SMALL target
     * offset would silently halve the post-rebuffer start threshold and make the player resume on a
     * sliver of buffer and immediately re-stall. At 20 s the 3_000 ms above survives intact.
     *
     * 20 seconds of latency is free here: this is a scraped NFL feed, not a betting terminal. Sitting
     * that far back means a segment that is late, briefly 404s, or is served by a lagging CDN node has
     * already been published by the time we need it — which removes a whole class of freezes at the
     * source rather than recovering from them. `createTimelineForLive` clamps the value down
     * automatically when the window is shorter, so it is safe on a 6-segment window. The asymmetric
     * speed range (0.97 / 1.05) is the shape live wants: after every recovery we are FURTHER behind
     * than before, and catching up is the entire point.
     */
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
                    // Only a PRE-playback buffer gets the full-screen spinner. A mid-stream rebuffer is
                    // left alone here on purpose: it is either a normal sub-3-second hiccup (which the
                    // watchdog correctly ignores) or a real stall the watchdog will promote to
                    // Recovering. What is NOT acceptable is the old behaviour — swallowing it and
                    // leaving the UI claiming Playing forever.
                    if (_uiState.value !is UiState.Playing && _uiState.value !is UiState.Recovering) {
                        _uiState.value = UiState.Buffering
                    }
                // STATE_IDLE / STATE_ENDED used to fall into `else -> Unit` and vanish. They are now
                // detected by the watchdog, which sees them alongside the position evidence.
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val kind = classify(error)
            diagnostics.breadcrumb(
                "live.error code=${error.errorCode} http=${httpStatusOf(error)} kind=$kind played=$hasReachedPlaying attempt=$attempt",
            )
            // An error leaves the player IDLE, so the watchdog's position evidence is meaningless from
            // here; drive the ladder directly off the error's own classification instead.
            recover(kind, bufferAdvancing = false)
        }
    }

    // --- Detection -------------------------------------------------------------------------------

    private fun startWatchdog() {
        watchdogJob?.cancel()
        val gen = generation
        watchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(LiveRecoveryPlan.WATCHDOG_TICK_MS)
                if (gen != generation) return@launch
                val p = _player.value ?: continue
                val now = SystemClock.elapsedRealtime()
                // Every read below touches the player from the main thread (viewModelScope) but is
                // still guarded: a player released underneath us must not take the whole screen down.
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
                    is LiveStallMonitor.Verdict.Stalled ->
                        recover(verdict.kind, verdict.bufferAdvancing)
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

        // The rung we applied is holding, so THIS freeze is over: restart the ladder at rung 0 so the
        // next, unrelated freeze gets the cheap instant seek again instead of inheriting a position on
        // the ladder it never earned. Without this, stalls arriving closer together than the clean-minute
        // budget climb straight past the repair that was working and abandon a healthy feed.
        if (attempt != 0 && LiveRecoveryPlan.recoveryConfirmed(playingForMs)) {
            diagnostics.breadcrumb("live.recover rung held ${playingForMs}ms on idx=$idx -> ladder reset")
            attempt = 0
        }

        // THE budget reset, and the reason a three-hour game survives. The counters are NOT monotonic:
        // a minute of clean playback forgives everything, so "freezes every few minutes but then
        // works" starts every episode at rung 0 and is rescued indefinitely. A feed stalling every few
        // seconds never reaches a clean minute and does escalate to the overlay, as it should.
        if (LiveRecoveryPlan.shouldResetBudget(playingForMs) &&
            (attempt != 0 || autoSwitchesSinceHealthy != 0 || feedHealth[idx]?.consecutiveFailures != 0)
        ) {
            diagnostics.breadcrumb("live.recover budget reset after ${playingForMs}ms clean on idx=$idx")
            attempt = 0
            autoSwitchesSinceHealthy = 0
            feedHealth[idx] = feedHealth[idx]!!.copy(consecutiveFailures = 0)
        }
    }

    // --- Escalation ------------------------------------------------------------------------------

    private fun recover(kind: LiveFailureKind, bufferAdvancing: Boolean) {
        // One repair in flight at a time. Without this the 1 s watchdog would stack a second rung on
        // top of a seek that is still fetching its chunk and race itself up to a teardown.
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
                nowMs = SystemClock.elapsedRealtime(),
                autoSwitchesSinceHealthy = autoSwitchesSinceHealthy,
            ),
        )
        diagnostics.breadcrumb("live.recover kind=$kind attempt=$attempt -> ${step.action} (${step.reason})")
        diagnostics.event(
            "live_recover",
            mapOf(
                "kind" to kind.name,
                "action" to step.action.name,
                "attempt" to attempt.toString(),
                "feeds" to feeds.size.toString(),
            ),
        )
        attempt++

        val p = _player.value
        recoveryJob = viewModelScope.launch {
            if (step.action != LiveRecoveryAction.GIVE_UP) {
                // HONEST UI: say we are fixing it instead of claiming to play a frozen picture.
                _uiState.value = UiState.Recovering(labelFor(step.action))
            }
            if (step.delayMs > 0) {
                delay(step.delayMs)
                // It fixed itself during the backoff — a load retry landed, or the CDN caught up. This
                // is the COMMON outcome on the rungs that back off, and seeking or re-preparing a
                // stream that is already moving again would be a self-inflicted second stall. Refund
                // the attempt too: a feed that keeps recovering on its own must not climb the ladder
                // for free and end up switched away from.
                if (_uiState.value is UiState.Playing) {
                    attempt = (attempt - 1).coerceAtLeast(0)
                    diagnostics.breadcrumb("live.recover self-healed during backoff, skipping ${step.action}")
                    return@launch
                }
            }
            if (gen != generation) return@launch
            when (step.action) {
                LiveRecoveryAction.SEEK_TO_LIVE_EDGE -> {
                    // Cheapest rung that can work: same MediaSource, same HlsMediaPeriod, same codec —
                    // one sample-queue reset and a chunk fetch. On a live timeline the default position
                    // IS the live edge (minus the target offset), which is what rejoining live means.
                    if (p != null && _player.value === p) {
                        runCatching { p.seekToDefaultPosition(); p.playWhenReady = true }
                    }
                    monitor.onRecoveryIssued(SystemClock.elapsedRealtime(), SEEK_SETTLE_MS)
                }
                LiveRecoveryAction.REPREPARE -> reprepare(p, gen)
                LiveRecoveryAction.REBUILD -> rebuild(gen)
                LiveRecoveryAction.SWITCH_FEED -> {
                    markFeedFailed(idx)
                    switchTo(step.targetFeedIndex, auto = true)
                }
                LiveRecoveryAction.GIVE_UP -> {
                    markFeedFailed(idx)
                    // Stop polling: the overlay now owns the screen, and a watchdog that kept firing
                    // would keep re-deciding GIVE_UP once a second for the rest of the session.
                    watchdogJob?.cancel()
                    _uiState.value = UiState.Error(
                        if (hasReachedPlaying) {
                            "This feed keeps dropping and we couldn't get it back. Try another source."
                        } else {
                            "This feed didn't load. Try another source."
                        },
                    )
                }
            }
        }
    }

    /**
     * Rung 2: restart the source in place, keeping the ExoPlayer, its codec and its PlayerView surface.
     *
     * The `stop()` is mandatory and non-obvious: `ExoPlayerImpl.prepare()` is verified to open with
     * `if (playbackInfo.playbackState != STATE_IDLE) return`, so re-preparing a player that is merely
     * BUFFERING or READY does NOTHING AT ALL. And the media item is re-SET rather than just prepared,
     * because `prepare()` retains the position (`resetPosition = false`) — re-preparing at a position
     * that has since slid out of the live window would take ERROR_CODE_BEHIND_LIVE_WINDOW immediately.
     * `setMediaItem(MediaItem)` resets the position, so the source picks the live default start.
     *
     * This is what fixes a stuck playlist, which a seek provably cannot: it restarts
     * DefaultHlsPlaylistTracker against the same URL and clears its earliestNextLoadTimeMs.
     */
    private fun reprepare(p: ExoPlayer?, gen: Int) {
        val url = resolvedUrl
        if (p == null || url == null || _player.value !== p || gen != generation) return
        runCatching {
            p.stop()
            p.setMediaItem(liveMediaItem(url))
            p.prepare()
            p.playWhenReady = true
        }
        monitor.onRecoveryIssued(SystemClock.elapsedRealtime(), PREPARE_SETTLE_MS)
    }

    /**
     * Rung 4: re-resolve the feed's URL and rebind it — the only rung allowed to pay the WebView cost,
     * which is why it is last rather than first. The old `retry()` did this (via a full `play()`) as
     * its ONLY response to anything.
     *
     * Two savings over the old teardown. The ExoPlayer, its surface and its codec are kept, because
     * the headers baked into `DefaultHttpDataSource.Factory` at build time belong to THIS feed and
     * have not changed — only a feed SWITCH needs a fresh factory. And the resolve runs with a much
     * shorter timeout: [WebViewStreamResolver]'s 40 s default is a cold-start budget, not something to
     * spend mid-game with the picture frozen.
     */
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
        if (url.isNullOrBlank()) {
            leaveDeadFeed("re-resolve found no url")
            return
        }
        val p = _player.value
        if (p != null) {
            resolvedUrl = url
            runCatching {
                p.stop()
                p.setMediaItem(liveMediaItem(url))
                p.prepare()
                p.playWhenReady = true
            }
            monitor.onRecoveryIssued(SystemClock.elapsedRealtime(), PREPARE_SETTLE_MS)
        } else {
            buildPlayer(url, feed.headers)
        }
    }

    /**
     * This feed cannot be repaired (nothing resolved, or the resolve threw). No ladder rung applies,
     * so move to the best remaining feed — or, when there genuinely isn't one, stop and say so rather
     * than rotating through dead feeds forever.
     */
    private fun leaveDeadFeed(why: String) {
        val idx = _currentIndex.value
        markFeedFailed(idx)
        val target = if (autoSwitchesSinceHealthy < LiveRecoveryPlan.maxAutoSwitches(feeds.size)) {
            LiveRecoveryPlan.chooseFeed(idx, feedInfos, feedHealth, SystemClock.elapsedRealtime())
        } else {
            null
        }
        diagnostics.breadcrumb("live.deadFeed idx=$idx why=$why -> ${target ?: "give up"}")
        if (target != null) {
            switchTo(target, auto = true)
        } else {
            _uiState.value = UiState.Error("Couldn't find a playable stream for this feed. Try another source.")
        }
    }

    private fun markFeedFailed(index: Int) {
        val h = feedHealth[index] ?: LiveFeedHealth()
        feedHealth[index] = h.copy(
            lastFailedAtMs = SystemClock.elapsedRealtime(),
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

    // --- Error classification --------------------------------------------------------------------

    /**
     * Turn a PlaybackException into the one fact the ladder needs: which repairs could possibly work.
     *
     * ERROR_CODE_BEHIND_LIVE_WINDOW (verified = 1002) is called out because media3 1.5.1 does NOT
     * self-heal it — `ExoPlayerImplInternal` routes `BehindLiveWindowException` to
     * `handleIoException(e, 1002)`, which is unconditionally fatal. The old code turned the single
     * most common live failure, whose documented recovery is one seek plus a prepare, into
     * "This feed didn't load" — a wrong message for a feed that had played fine for ten minutes.
     */
    private fun classify(error: PlaybackException): LiveFailureKind {
        val http = httpStatusOf(error)
        return when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> LiveFailureKind.BEHIND_LIVE_WINDOW
            // 401/403/410 = the embed's rotating, JS-minted token is dead. Indistinguishable from a
            // blip by error code alone (both arrive as ERROR_CODE_IO_BAD_HTTP_STATUS = 2004), and the
            // difference is decisive: no amount of re-requesting a dead signed URL helps, only a
            // re-resolve does. Deliberately NOT gated on hasReachedPlaying — a token can expire in the
            // seconds between the WebView capturing it and ExoPlayer asking for it, which produces the
            // same dead URL before the feed ever played a frame.
            http in STALE_TOKEN_STATUSES -> LiveFailureKind.STALE_URL
            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> LiveFailureKind.TRANSIENT_IO
            error.errorCode in 2000..2999 -> LiveFailureKind.TRANSIENT_IO
            else -> LiveFailureKind.FATAL
        }
    }

    /** The HTTP status buried in the cause chain, if this was an InvalidResponseCodeException. */
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

    /** Manual retry from the error overlay: a clean restart of this feed with the budget reset. */
    fun retry() {
        switchTo(_currentIndex.value, auto = false)
    }

    override fun onCleared() {
        generation++
        watchdogJob?.cancel()
        recoveryJob?.cancel()
        playJob?.cancel()
        _player.value?.release()
        _player.value = null
        super.onCleared()
    }

    private companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

        /** Half the stock 8_000 ms DefaultHttpDataSource connect/read timeouts. */
        const val LIVE_HTTP_TIMEOUT_MS = 4_000

        /** Mid-game re-resolve budget. The resolver's 40 s default is for a cold start, not a freeze. */
        const val RE_RESOLVE_TIMEOUT_MS = 12_000L

        /** How long a seek gets to land before the watchdog is allowed to judge it. */
        const val SEEK_SETTLE_MS = 4_000L

        /** A re-prepare has to re-fetch the playlist and refill, so it gets longer. */
        const val PREPARE_SETTLE_MS = 6_000L

        val STALE_TOKEN_STATUSES = setOf(401, 403, 410)

        const val CAUSE_SCAN_DEPTH = 6
    }
}
