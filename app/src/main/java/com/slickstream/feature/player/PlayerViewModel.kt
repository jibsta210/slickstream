package com.slickstream.feature.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.mediarouter.media.MediaRouteSelector
import com.slickstream.cast.CastManager
import com.slickstream.core.common.DeviceProfile
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.Episode
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.StreamSource
import com.slickstream.core.model.StreamState
import com.slickstream.core.model.StreamStatus
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.SourceRepository
import com.slickstream.core.repository.TorrentStreamer
import com.slickstream.core.model.SubtitleTrack
import com.slickstream.data.settings.QualityPreference
import com.slickstream.data.settings.SettingsRepository
import com.slickstream.data.settings.StreamSizePreference
import com.slickstream.data.source.StreamPicker
import com.slickstream.data.vlc.VlcEngine
import com.slickstream.data.vlc.VlcPlayer
import com.slickstream.data.settings.SubtitleSize
import com.slickstream.data.settings.SubtitleStyle
import com.slickstream.data.subtitle.SubtitleRepository
import com.slickstream.navigation.NavArg
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the player overlay renders. The ExoPlayer instance is exposed separately. */
sealed interface PlayerUiState {
    /** Resolving sources / fetching torrent metadata / pre-buffering. */
    data class Buffering(
        val percent: Int,
        val seeders: Int,
        val downloadRateBytes: Int,
        val label: String,
        /** Rough seconds until playback can start (head buffer / rate); null when not estimable. */
        val etaSeconds: Int? = null,
    ) : PlayerUiState

    /** Player has a stream URL and is (or will shortly be) playing. */
    data object Playing : PlayerUiState

    data class Error(val message: String) : PlayerUiState
}

/** Subtitle appearance the players apply to their Media3 SubtitleView. */
data class CaptionPrefs(val size: SubtitleSize, val style: SubtitleStyle)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val catalogRepository: CatalogRepository,
    private val sourceRepository: SourceRepository,
    private val torrentStreamer: TorrentStreamer,
    private val libraryRepository: LibraryRepository,
    private val castManager: CastManager,
    private val settingsRepository: SettingsRepository,
    private val subtitleRepository: SubtitleRepository,
    private val deviceProfile: DeviceProfile,
    private val vlcEngine: VlcEngine,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // --- Nav args -----------------------------------------------------------
    private val mediaType: MediaType =
        MediaType.fromName(savedStateHandle.get<String>(NavArg.MEDIA_TYPE))
    private val mediaId: Int = savedStateHandle.get<Int>(NavArg.MEDIA_ID) ?: -1
    private val navSeason: Int? =
        savedStateHandle.get<Int>(NavArg.SEASON)?.takeIf { it >= 0 }
    private val navEpisode: Int? =
        savedStateHandle.get<Int>(NavArg.EPISODE)?.takeIf { it >= 0 }

    // The currently-playing episode. Mutable so in-player navigation (next/previous/auto-advance)
    // can switch episodes without recreating the screen. Movies leave these null and behave exactly
    // as before. Seeded from the nav args.
    private var currentSeason: Int? = navSeason
    private var currentEpisode: Int? = navEpisode

    // --- UI state -----------------------------------------------------------
    private val _uiState = MutableStateFlow<PlayerUiState>(
        PlayerUiState.Buffering(0, 0, 0, "Loading…")
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _sources = MutableStateFlow<List<StreamSource>>(emptyList())
    val sources: StateFlow<List<StreamSource>> = _sources.asStateFlow()

    private val _currentSource = MutableStateFlow<StreamSource?>(null)
    val currentSource: StateFlow<StreamSource?> = _currentSource.asStateFlow()

    /**
     * One-shot "this is taking a while — try a smaller stream" hint. Set true only when a buffer has
     * dragged on past [BUFFERING_NAG_MS] with a low download rate AND a smaller, well-seeded source
     * actually exists below the current one. Reset on Playing / source switch / dismiss. Shown at
     * most once per source so we never nag.
     */
    private val _suggestSmaller = MutableStateFlow(false)
    val suggestSmaller: StateFlow<Boolean> = _suggestSmaller.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    /** The active player surface the UI binds to — local ExoPlayer or the remote CastPlayer. */
    private val _currentPlayer = MutableStateFlow<androidx.media3.common.Player?>(null)
    val currentPlayer: StateFlow<androidx.media3.common.Player?> = _currentPlayer.asStateFlow()

    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _title = MutableStateFlow("Now Playing")
    val title: StateFlow<String> = _title.asStateFlow()

    // --- Episode navigation (TV only) ---------------------------------------
    /** Episodes of the current season — empty for movies; drives the episode-list UI. */
    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    /** Currently-playing episode number within [episodes] (null for movies). */
    private val _currentEpisodeNumber = MutableStateFlow<Int?>(navEpisode)
    val currentEpisodeNumber: StateFlow<Int?> = _currentEpisodeNumber.asStateFlow()

    /** Currently-playing season number (null for movies). */
    private val _currentSeasonNumber = MutableStateFlow<Int?>(navSeason)
    val currentSeasonNumber: StateFlow<Int?> = _currentSeasonNumber.asStateFlow()

    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext.asStateFlow()

    private val _hasPrevious = MutableStateFlow(false)
    val hasPrevious: StateFlow<Boolean> = _hasPrevious.asStateFlow()

    // --- Internal -----------------------------------------------------------
    private var details: MediaDetails? = null
    private var streamJob: Job? = null
    private var progressTickJob: Job? = null
    private var activeInfoHash: String? = null
    private var hasSeekedToResume = false
    /** Consecutive transient source-error re-prepares; reset on a fresh source or once playing. */
    private var sourceErrorRetries = 0
    private var playerListener: Player.Listener? = null
    private var currentMediaUrl: String? = null
    private var castPlayer: CastPlayer? = null

    /**
     * libVLC-backed fallback player for codecs ExoPlayer can't decode (XviD/DivX/AVI/WMV…). Built
     * lazily only when the active source is flagged not-[StreamSource.playable] (or when ExoPlayer
     * hard-fails to parse it). Plays everything desktop VLC can. The UI attaches its video surface.
     */
    private var vlcPlayer: VlcPlayer? = null
    /** Set once we've fallen back to VLC for the current source, so we don't bounce back to ExoPlayer. */
    private var usingVlcForSource = false

    // --- Automatic source failover ---
    /** infoHashes already attempted for THIS title/episode — failover never re-picks one. */
    private val triedInfoHashes = mutableSetOf<String>()
    /** Automatic failovers since the last fresh load()/episode switch; capped to avoid churn. */
    private var failoverCount = 0
    /** Set synchronously while a failover is in progress so two triggers can't fire it twice. */
    private var failoverInFlight = false
    /** Watchdog that fails over when a source never reaches playable (dead / fake-seeded swarm). */
    private var bufferWatchdogJob: Job? = null
    /** Last downloadedBytes the streamer emitted — the watchdog's head-progress signal. */
    @Volatile private var lastEmittedDownloadedBytes: Long = 0L

    // --- "Stuck buffering -> try a smaller stream" hint ---
    /** When the current continuous buffer began (uptime ms); 0 while not buffering. */
    private var bufferingSinceMs: Long = 0L
    /** Already shown the smaller-stream hint for this source — don't nag again. */
    private var suggestedSmallerForSource = false
    /** Effective quality cap from the last auto-pick — reused by the synchronous smaller-source scan. */
    @Volatile private var currentNetworkMaxTier: Int? = null

    // --- Next-episode prefetch ---
    private var prefetchJob: Job? = null
    private var prefetchTriggeredForEpisode = false
    @Volatile private var prefetchedInfoHash: String? = null

    // --- PiP: current video aspect ratio for PictureInPictureParams (clamped at use site) ---
    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    // --- Subtitles ---
    private val _subtitles = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitles: StateFlow<List<SubtitleTrack>> = _subtitles.asStateFlow()
    private val _currentSubtitle = MutableStateFlow<SubtitleTrack?>(null)
    val currentSubtitle: StateFlow<SubtitleTrack?> = _currentSubtitle.asStateFlow()
    private var subtitleConfigs: List<ExoMediaItem.SubtitleConfiguration> = emptyList()
    private var preferredSubCode: String? = null

    /** User's subtitle size + style, live — players apply this to their SubtitleView. */
    val captionPrefs: StateFlow<CaptionPrefs> = settingsRepository.settings
        .map { CaptionPrefs(it.subtitleSize, it.subtitleStyle) }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            CaptionPrefs(SubtitleSize.DEFAULT, SubtitleStyle.DEFAULT),
        )

    /** Outlives [viewModelScope] so final progress + torrent-stop survive onCleared(). */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        load()
    }

    fun retry() {
        _uiState.value = PlayerUiState.Buffering(0, 0, 0, "Loading…")
        load()
    }

    // --- Cast (Chromecast) state for the UI ---------------------------------
    val castAvailable: Boolean get() = castManager.isAvailable
    val castMergedSelector: MediaRouteSelector? get() = castManager.castContext?.mergedSelector
    fun isCastConnected(): Boolean = castManager.isConnected()

    /** Stop casting and continue on this device. The CastPlayer's onCastSessionUnavailable()
     *  callback (wired in ensureCastPlayer) drives transferToLocal() at the cast position. */
    fun stopCasting() = castManager.stopCasting(stopReceiver = true)

    private fun load() {
        viewModelScope.launch {
            _uiState.value = PlayerUiState.Buffering(0, 0, 0, "Fetching details…")
            val d = when (val r = catalogRepository.getDetails(mediaId, mediaType)) {
                is DataResult.Success -> r.data
                is DataResult.Error -> {
                    _uiState.value = PlayerUiState.Error(r.message)
                    return@launch
                }
            }
            details = d
            _title.value = buildTitle(d)
            viewModelScope.launch { loadSubtitles(d) }
            viewModelScope.launch { loadEpisodeList() }

            _uiState.value = PlayerUiState.Buffering(0, 0, 0, "Finding sources…")
            val list = when (val r = sourceRepository.resolve(d, currentSeason, currentEpisode)) {
                is DataResult.Success -> r.data
                is DataResult.Error -> {
                    _uiState.value = PlayerUiState.Error(r.message)
                    return@launch
                }
            }
            if (list.isEmpty()) {
                _uiState.value = PlayerUiState.Error("No streamable sources found.")
                return@launch
            }
            _sources.value = list.sortedByDescending { it.rank }
            // Fresh title -> fresh failover budget over the new candidate list.
            triedInfoHashes.clear()
            failoverCount = 0

            // Auto-pick the best source within the user's per-network quality cap.
            val best = pickPreferred(list, networkQualityPreference())
            startSource(best)
        }
    }

    /** Switch to a different quality/source, keeping the previous download cached. */
    fun selectSource(source: StreamSource) {
        if (source.infoHash == _currentSource.value?.infoHash) return
        // A source switch clears any pending hint; startSource() re-arms it for the new source.
        _suggestSmaller.value = false
        viewModelScope.launch {
            // Persist where we are before tearing the current stream down.
            saveProgressNow()
            bufferWatchdogJob?.cancel()
            stopActiveStream(removeFiles = false)
            startSource(source)
        }
    }

    /**
     * Act on the "try a smaller stream" hint: switch to the smallest healthy source below the
     * current one (via [StreamPicker]). No-op if none qualifies — the hint just clears.
     */
    fun switchToSmaller() {
        val smaller = findSmallerHealthySource()
        _suggestSmaller.value = false
        if (smaller != null) selectSource(smaller)
    }

    /** Dismiss the smaller-stream hint without switching. Won't reappear for this source. */
    fun dismissSuggestSmaller() {
        _suggestSmaller.value = false
    }

    private suspend fun networkQualityPreference(): QualityPreference {
        val settings = settingsRepository.current()
        return if (isOnUnmeteredNetwork()) settings.wifiQuality else settings.cellularQuality
    }

    /**
     * Auto-pick a source. SEEDERS are the primary key (with a health floor) — NOT quality rank —
     * so a starved 4K/3-seeder is never chosen over a healthy 1080p/500-seeder. On low-power TV we
     * also cap at 1080p under AUTO, since 4K is the heaviest to sustain on the conservative engine
     * profile (and the dominant cause of "playback failed" on TV). Quality only tiebreaks.
     */
    private suspend fun pickPreferred(list: List<StreamSource>, pref: QualityPreference): StreamSource {
        val sizePref = settingsRepository.current().streamSize
        currentNetworkMaxTier = pref.maxTier   // cache for the synchronous smaller-source scan
        return StreamPicker.pick(list, pref.maxTier, sizePref, deviceProfile.isLowPower) ?: list.first()
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        // Authoritative: the system's own "not metered" signal (handles metered Wi-Fi correctly).
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        // Wi-Fi / Ethernet use the "Wi-Fi" cap; cellular uses the mobile-data cap.
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun startSource(source: StreamSource) {
        _currentSource.value = source
        hasSeekedToResume = false
        activeInfoHash = source.infoHash
        // Record this attempt so failover never loops back to a source we've already tried, and clear
        // the in-flight guard now that a new attempt is actually underway.
        triedInfoHashes.add(source.infoHash)
        failoverInFlight = false
        lastEmittedDownloadedBytes = 0L
        // Fresh source -> reset the VLC-fallback latch and stop any prior VLC playback so it can't
        // keep playing audio underneath the new source.
        usingVlcForSource = false
        vlcPlayer?.let { runCatching { it.stop() } }
        prefetchTriggeredForEpisode = false
        prefetchJob?.cancel()
        // Fresh source — re-arm the smaller-stream hint and start its buffering clock.
        suggestedSmallerForSource = false
        _suggestSmaller.value = false
        bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
        _uiState.value = PlayerUiState.Buffering(
            percent = 0,
            seeders = source.seeders ?: 0,
            downloadRateBytes = 0,
            label = "Connecting to peers…",
        )

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            torrentStreamer.start(source).collect { status ->
                handleStatus(status, source)
            }
        }

        // Buffering watchdog: the dead-/fake-seeded-swarm case never reaches onPlayerError (no player
        // is ever built), so it would otherwise spin on "Buffering…" forever. If this source hasn't
        // become playable within the budget AND the contiguous head hasn't grown for a while, fail
        // over to the next source. The dual condition (total budget + flat head) distinguishes a
        // genuinely dead swarm from a slow-but-alive one, so we never punish a swarm still trickling in.
        bufferWatchdogJob?.cancel()
        bufferWatchdogJob = viewModelScope.launch {
            var lastHead = -1L
            var lastProgressAt = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                if (_uiState.value is PlayerUiState.Playing) return@launch
                val now = android.os.SystemClock.elapsedRealtime()
                val head = lastEmittedDownloadedBytes
                if (head > lastHead) {
                    lastHead = head
                    lastProgressAt = now
                }
                val sinceStart = now - bufferingSinceMs
                val stalled = now - lastProgressAt
                if (sinceStart >= FAILOVER_BUFFER_BUDGET_MS && stalled >= WATCHDOG_STALL_MS) {
                    if (!failoverToNext()) {
                        _uiState.value = PlayerUiState.Error("Couldn't start any source for this title.")
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * Advance to the next-best UNTRIED source automatically. Returns true if another source was
     * started, false when none remain (the caller then shows the terminal error). Walks DOWN the same
     * ranked list the original auto-pick used (same quality cap + size pref), skipping every hash we
     * already tried — so a dead/fake-seeded swarm, a missing-moov mp4, or an unplayable container
     * self-heals to a real source instead of spinning or dead-ending on a manual "Other streams".
     */
    private suspend fun failoverToNext(): Boolean {
        if (failoverInFlight) return true
        if (failoverCount >= MAX_FAILOVERS) return false
        val remaining = _sources.value.filter { it.infoHash !in triedInfoHashes }
        if (remaining.isEmpty()) return false
        // Claim synchronously (before any suspension) so the watchdog + onPlayerError can't both fire.
        failoverInFlight = true
        failoverCount++
        val pref = currentNetworkMaxTier ?: networkQualityPreference().maxTier
        val sizePref = settingsRepository.current().streamSize
        val next = StreamPicker.pick(remaining, pref, sizePref, deviceProfile.isLowPower)
            ?: remaining.first()
        _suggestSmaller.value = false
        _uiState.value = PlayerUiState.Buffering(
            percent = 0,
            seeders = next.seeders ?: 0,
            downloadRateBytes = 0,
            label = "Trying another source…",
        )
        // Keep the just-failed partial in cache, then tear the poisoned player down so ensurePlayer()
        // rebuilds a clean one on the new URL. startSource() re-arms failoverInFlight = false.
        stopActiveStream(removeFiles = false)
        teardownPlayerForFailover()
        startSource(next)
        return true
    }

    /** Release the current (dead-ended) ExoPlayer so the next [ensurePlayer] builds a clean one. */
    private fun teardownPlayerForFailover() {
        bufferWatchdogJob?.cancel()
        progressTickJob?.cancel()
        val exo = _player.value
        playerListener?.let { exo?.removeListener(it) }
        exo?.release()
        _player.value = null
        vlcPlayer?.let { runCatching { it.stop() } }
        if (!_isCasting.value) _currentPlayer.value = null
        currentMediaUrl = null
        sourceErrorRetries = 0
    }

    private fun handleStatus(status: StreamStatus, source: StreamSource) {
        // Feed the buffering watchdog's head-progress signal on every emission.
        lastEmittedDownloadedBytes = status.downloadedBytes
        val url = status.streamUrl
        if (url != null) {
            ensurePlayer(url)
            if (status.state == StreamState.ERROR && status.errorMessage != null) {
                _uiState.value = PlayerUiState.Error(status.errorMessage!!)
                return
            }
            // The URL is up and the player is now attaching: fetching the moov/tail and filling its
            // own buffer. The torrent keeps reporting progress through this phase, so KEEP showing an
            // ETA-to-playing here instead of dropping to a blank "Buffering…" — that post-download gap
            // is exactly what made the old estimate feel wrong (it counted only the raw download).
            // Don't clobber an already-Playing state.
            if (_uiState.value is PlayerUiState.Playing) return
            _uiState.value = PlayerUiState.Buffering(
                percent = (status.progress * 100f).toInt().coerceIn(0, 100),
                seeders = status.seeders.takeIf { it > 0 } ?: (source.seeders ?: 0),
                downloadRateBytes = status.downloadRateBytes,
                label = "Almost ready…",
                etaSeconds = etaToPlaying(status),
            )
            return
        }

        if (status.state == StreamState.ERROR) {
            // Metadata fetch failed / engine unavailable / no-playable-file (RAR release). Auto-advance
            // to the next untried source before ever showing a terminal error.
            val msg = status.errorMessage ?: "Streaming failed."
            viewModelScope.launch {
                if (!failoverToNext()) _uiState.value = PlayerUiState.Error(msg)
            }
            return
        }

        // No URL yet — still pre-buffering. Don't clobber a Playing state.
        if (_uiState.value is PlayerUiState.Playing) return

        val label = when (status.state) {
            StreamState.METADATA -> "Fetching torrent metadata…"
            StreamState.BUFFERING -> "Buffering…"
            StreamState.IDLE -> "Connecting to peers…"
            else -> "Buffering…"
        }
        _uiState.value = PlayerUiState.Buffering(
            percent = (status.progress * 100f).toInt().coerceIn(0, 100),
            seeders = status.seeders.takeIf { it > 0 } ?: (source.seeders ?: 0),
            downloadRateBytes = status.downloadRateBytes,
            label = label,
            etaSeconds = etaToPlaying(status),
        )
        maybeSuggestSmaller(status.downloadRateBytes)
    }

    /**
     * Estimated seconds until the video ACTUALLY starts playing — not just until the head download
     * finishes. = (bytes still needed to be playable ÷ current rate) + a fixed margin for the
     * moov/tail fetch + ExoPlayer prepare that always happen AFTER the head fills. Once enough is
     * downloaded the estimate settles to that margin and keeps counting down through the player's own
     * buffering, so it no longer drops to a blank "Buffering…" right before playback. Null when more
     * bytes are needed but the rate is too low to estimate.
     */
    private fun etaToPlaying(status: StreamStatus): Int? {
        val needed = (PLAYABLE_TARGET_BYTES - status.downloadedBytes).coerceAtLeast(0L)
        val downloadSecs = when {
            needed == 0L -> 0
            status.downloadRateBytes > 0 -> (needed / status.downloadRateBytes).toInt()
            else -> return null
        }
        return (downloadSecs + PREPARE_MARGIN_SECONDS).takeIf { it in 1..900 }
    }

    /**
     * Gentle nudge: if the head-buffer has dragged on past [BUFFERING_NAG_MS] with a low download
     * rate AND a smaller, well-seeded source exists below the current one, raise the one-shot
     * [suggestSmaller] hint. Fires at most once per source ([suggestedSmallerForSource]).
     */
    private fun maybeSuggestSmaller(downloadRateBytes: Int) {
        if (suggestedSmallerForSource) return
        if (bufferingSinceMs == 0L) return
        val elapsed = android.os.SystemClock.elapsedRealtime() - bufferingSinceMs
        if (elapsed < BUFFERING_NAG_MS) return
        if (downloadRateBytes >= BUFFERING_LOW_RATE_BYTES) return
        if (findSmallerHealthySource() == null) return
        suggestedSmallerForSource = true
        _suggestSmaller.value = true
    }

    /**
     * The smallest healthy source strictly smaller than the current one — reuses [StreamPicker] over
     * a SMALLEST size preference, then verifies it is genuinely below the current size. Null when no
     * such source exists (so we never suggest a switch that wouldn't help).
     */
    private fun findSmallerHealthySource(): StreamSource? {
        val current = _currentSource.value ?: return null
        val currentSize = current.sizeBytes ?: return null
        val pref = currentNetworkMaxTier ?: return null
        val candidate = StreamPicker.pick(
            list = _sources.value,
            maxTier = pref,
            sizePref = StreamSizePreference.SMALLEST,
            lowPower = deviceProfile.isLowPower,
        ) ?: return null
        if (candidate.infoHash == current.infoHash) return null
        val candidateSize = candidate.sizeBytes ?: return null
        return if (candidateSize < currentSize) candidate else null
    }

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(url: String) {
        // A source ExoPlayer can't decode (XviD/AVI/WMV…) goes straight to the libVLC fallback so it
        // actually plays instead of black-screening. usingVlcForSource latches once we've switched.
        if (usingVlcForSource || _currentSource.value?.playable == false) {
            ensureVlcPlayer(url)
            return
        }
        val existing = _player.value
        if (existing != null) {
            // Only reload on a genuine URL change (source switch); steady-state
            // READY re-emissions carry the same URL and must be no-ops.
            if (currentMediaUrl == url) return
            existing.setMediaItem(buildMediaItem(url))
            existing.prepare()
            // While casting, keep the local surface paused and push the new source to the TV.
            existing.playWhenReady = !_isCasting.value
            sourceErrorRetries = 0
            currentMediaUrl = url
            if (_isCasting.value) loadCastMedia(fromPositionMs = 0L)
            return
        }

        // Start playing with a small buffer so we don't sit idle while the torrent fills ahead.
        // On low-power TV, hard-cap the buffer to ~8 MB: the default 50s duration buffer can hold
        // 60-95 MB of a high-bitrate stream — identical on a phone and a 1.5 GB TV — which drives the
        // OOM/page-eviction pressure that freezes the whole device. bufferForPlaybackMs stays 1_500 so
        // first-frame latency is unchanged.
        val lowPower = deviceProfile.isLowPower
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (lowPower) 8_000 else 15_000,  // minBufferMs
                if (lowPower) 20_000 else 50_000, // maxBufferMs
                1_500,                            // bufferForPlaybackMs — begin playback this fast
                3_000,                            // bufferForPlaybackAfterRebufferMs
            )
            .apply {
                if (lowPower) {
                    setTargetBufferBytes(8 * 1024 * 1024)
                    setPrioritizeTimeOverSizeThresholds(false)
                }
            }
            .build()
        // Decoder fallback: on a weak TV a single HEVC/10-bit/4K decoder-init failure would
        // otherwise dead-end straight into Error; fallback retries on a secondary/software decoder.
        // No behaviour change when the primary decoder works (the phone path).
        val renderers = DefaultRenderersFactory(appContext).setEnableDecoderFallback(true)
        // Keep playing through a torrent stall. Our local HTTP server throws when a piece isn't on
        // disk YET (slow swarm) — ExoPlayer would otherwise treat that as a FATAL source error and
        // kill the stream. A high source-retry count makes it rebuffer + re-request the byte range
        // (the bytes are downloading) instead. This is the real fix for "the stream died".
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(SOURCE_LOAD_RETRIES))
        val exo = ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setMediaItem(buildMediaItem(url))
                prepare()
                playWhenReady = true
            }
        sourceErrorRetries = 0
        currentMediaUrl = url

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        sourceErrorRetries = 0 // recovered / playing — forget transient stalls
                        maybeSeekToResume(exo)
                        // Playing — clear the buffering clock and any pending smaller-stream hint.
                        bufferingSinceMs = 0L
                        _suggestSmaller.value = false
                        _uiState.value = PlayerUiState.Playing
                    }
                    Player.STATE_BUFFERING -> {
                        if (_uiState.value !is PlayerUiState.Playing) {
                            val s = _currentSource.value
                            // (Re)start the buffering clock if it was cleared by a prior READY.
                            if (bufferingSinceMs == 0L) {
                                bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
                            }
                            _uiState.value = PlayerUiState.Buffering(
                                percent = 100,
                                seeders = s?.seeders ?: 0,
                                downloadRateBytes = 0,
                                label = "Buffering…",
                            )
                            maybeSuggestSmaller(downloadRateBytes = 0)
                        }
                    }
                    Player.STATE_ENDED -> {
                        saveProgressNow()
                        // Auto-play the next episode of a TV series when one finishes.
                        if (mediaType == MediaType.TV && _hasNext.value) nextEpisode()
                    }
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveProgressNow()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val w = videoSize.width
                val h = videoSize.height
                if (w > 0 && h > 0) {
                    val par = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                    _videoAspect.value = (w * par) / h
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val p = _player.value
                // Transient on a torrent, NOT a dead stream:
                //  - IO (2000-2999): pieces aren't downloaded YET.
                //  - PARSING (3000-3999): ExoPlayer started via tail-grace before the mp4 moov/mkv
                //    cues fully arrived, so it couldn't parse — re-preparing once they land fixes it.
                //    (This is the "buffered fast, started, then playback error" case.)
                // Back off to let bytes arrive, then re-prepare (position retained) and keep playing.
                // Only show the error screen once retries are genuinely exhausted (truly dead / a codec
                // the device can't decode, which won't recover and is what "Other streams" is for).
                // IO (2000-2999) = pieces not here yet -> retry generously. PARSING (3000-3999) =
                // possibly a late moov, but on a genuinely bad container/codec it just loops in a
                // black screen, so cap it LOW: a couple of quick re-prepares, then fail to the error
                // screen (which offers "Other streams" + shows the code) rather than looping forever.
                val isIo = error.errorCode in 2000..2999
                val isParse = error.errorCode in 3000..3999
                val cap = if (isParse) 2 else MAX_SOURCE_RETRIES
                if ((isIo || isParse) && !_isCasting.value && p != null && sourceErrorRetries < cap) {
                    sourceErrorRetries++
                    _uiState.value = PlayerUiState.Buffering(
                        percent = 0,
                        seeders = _currentSource.value?.seeders ?: 0,
                        downloadRateBytes = 0,
                        label = "Reconnecting…",
                    )
                    viewModelScope.launch {
                        delay(SOURCE_RETRY_BACKOFF_MS * sourceErrorRetries)
                        runCatching { p.prepare(); p.playWhenReady = true }
                    }
                } else if (isParse && !usingVlcForSource && !_isCasting.value && currentMediaUrl != null) {
                    // ExoPlayer can't parse/decode this container/codec even after retries (e.g. an
                    // XviD/AVI the codec heuristic didn't catch). Hand the SAME source to the libVLC
                    // fallback (bundles FFmpeg, plays everything) at the current position before giving
                    // up on it — this is the "every codec works" path.
                    val pos = _currentPlayer.value?.currentPosition?.coerceAtLeast(0L) ?: 0L
                    switchToVlc(currentMediaUrl!!, pos)
                } else {
                    // Per-source retries are spent and VLC can't help (or already tried). Before
                    // dead-ending, hand off to the next untried source automatically; only surface the
                    // error (with its code) once every candidate is exhausted. Casting can't fail over.
                    val msg = error.localizedMessage ?: "Playback error"
                    if (!_isCasting.value) {
                        viewModelScope.launch {
                            if (!failoverToNext()) {
                                _uiState.value = PlayerUiState.Error("$msg (${error.errorCodeName})")
                            }
                        }
                    } else {
                        _uiState.value = PlayerUiState.Error("$msg (${error.errorCodeName})")
                    }
                }
            }
        }
        exo.addListener(listener)
        playerListener = listener
        _player.value = exo
        if (!_isCasting.value) _currentPlayer.value = exo
        if (preferredSubCode != null) applyPreferredSub()
        startProgressTicker()
        ensureCastPlayer()
    }

    /**
     * Build (or reuse) the libVLC fallback player and hand it the stream. VlcPlayer is a Media3
     * [Player], so the existing PlayerView / TV transport / progress logic drive it unchanged — the
     * screens just attach its video surface. We tear down any ExoPlayer first (a source is played by
     * exactly one backend at a time).
     */
    @OptIn(UnstableApi::class)
    private fun ensureVlcPlayer(url: String, startPositionMs: Long = 0L) {
        usingVlcForSource = true
        val existing = vlcPlayer
        if (existing != null && currentMediaUrl == url) return  // steady-state READY re-emit -> no-op

        // One backend at a time: drop the ExoPlayer if it was the one showing.
        _player.value?.let { exo ->
            playerListener?.let { exo.removeListener(it) }
            exo.release()
        }
        _player.value = null
        playerListener = null

        val vlc = existing ?: VlcPlayer(vlcEngine, appContext).also { built ->
            vlcPlayer = built
            built.addListener(buildVlcListener(built))
        }
        vlc.setMediaItem(buildMediaItem(url), startPositionMs)
        vlc.prepare()
        vlc.playWhenReady = !_isCasting.value
        currentMediaUrl = url
        if (!_isCasting.value) _currentPlayer.value = vlc
        startProgressTicker()
    }

    /** Lean transport listener for the VLC backend (READY -> Playing + resume, ENDED -> next). */
    private fun buildVlcListener(vlc: VlcPlayer): Player.Listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    maybeSeekToResume(vlc)
                    bufferingSinceMs = 0L
                    _suggestSmaller.value = false
                    _uiState.value = PlayerUiState.Playing
                }
                Player.STATE_BUFFERING -> {
                    if (_uiState.value !is PlayerUiState.Playing) {
                        _uiState.value = PlayerUiState.Buffering(
                            percent = 100,
                            seeders = _currentSource.value?.seeders ?: 0,
                            downloadRateBytes = 0,
                            label = "Buffering…",
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    saveProgressNow()
                    if (mediaType == MediaType.TV && _hasNext.value) nextEpisode()
                }
                else -> Unit
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            val w = videoSize.width
            val h = videoSize.height
            if (w > 0 && h > 0) {
                val par = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                _videoAspect.value = (w * par) / h
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgressNow()
        }
    }

    /** Hand the CURRENT source to VLC at [startPositionMs] (ExoPlayer couldn't decode it). */
    private fun switchToVlc(url: String, startPositionMs: Long) {
        _uiState.value = PlayerUiState.Buffering(
            percent = 0,
            seeders = _currentSource.value?.seeders ?: 0,
            downloadRateBytes = 0,
            label = "Switching player…",
        )
        currentMediaUrl = null   // force ensureVlcPlayer to (re)load even if the url matches
        ensureVlcPlayer(url, startPositionMs)
    }

    /** Release the VLC fallback player, if any. */
    private fun releaseVlcPlayer() {
        vlcPlayer?.let { runCatching { it.release() } }
        vlcPlayer = null
    }

    // --- Subtitles ----------------------------------------------------------

    private suspend fun loadSubtitles(d: MediaDetails) {
        val settings = settingsRepository.current()
        preferredSubCode = if (settings.subtitlesEnabled) settings.subtitleLanguage.code else null
        val subs = subtitleRepository.fetch(d, currentSeason, currentEpisode)
        _subtitles.value = subs
        subtitleConfigs = subs.map(::buildSubConfig)
        // If the player already started before subs arrived, re-attach them at the live position.
        val exo = _player.value ?: return
        val url = currentMediaUrl ?: return
        if (subtitleConfigs.isNotEmpty()) {
            val pos = exo.currentPosition
            exo.setMediaItem(buildMediaItem(url), pos)
            exo.prepare()
        }
        if (preferredSubCode != null) applyPreferredSub()
    }

    /** Pick an external/embedded subtitle track (null = turn subtitles off). */
    fun selectSubtitle(track: SubtitleTrack?) {
        val exo = _player.value ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().apply {
            if (track == null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setPreferredTextLanguage(track.languageCode)
            }
        }.build()
        _currentSubtitle.value = track
    }

    /** Re-query the subtitle addon (the in-player "search") and re-attach to the live player. */
    fun refreshSubtitles() {
        val d = details ?: return
        viewModelScope.launch {
            val subs = subtitleRepository.fetch(d, currentSeason, currentEpisode)
            _subtitles.value = subs
            subtitleConfigs = subs.map(::buildSubConfig)
            val exo = _player.value ?: return@launch
            val url = currentMediaUrl ?: return@launch
            exo.setMediaItem(buildMediaItem(url), exo.currentPosition)
            exo.prepare()
            _currentSubtitle.value?.let { selectSubtitle(it) }
        }
    }

    private fun applyPreferredSub() {
        val code = preferredSubCode ?: return
        val exo = _player.value ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(code)
            .build()
        _currentSubtitle.value = _subtitles.value.firstOrNull { it.languageCode.equals(code, true) }
    }

    private fun buildMediaItem(url: String): ExoMediaItem =
        ExoMediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

    private fun buildSubConfig(track: SubtitleTrack): ExoMediaItem.SubtitleConfiguration =
        ExoMediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
            .setMimeType(subtitleMime(track.url))
            .setLanguage(track.languageCode)
            .setLabel(track.label)
            .build()

    private fun subtitleMime(url: String): String = when {
        url.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
        url.endsWith(".ass", ignoreCase = true) || url.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    // --- Cast playback transfer ---------------------------------------------

    @OptIn(UnstableApi::class)
    private fun ensureCastPlayer() {
        if (castPlayer != null) return
        val ctx = castManager.castContext ?: return
        val cp = CastPlayer(ctx)
        cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = transferToCast()
            override fun onCastSessionUnavailable() = transferToLocal()
        })
        castPlayer = cp
        // A Cast session may already be live when the player screen opens.
        if (cp.isCastSessionAvailable) transferToCast()
    }

    @OptIn(UnstableApi::class)
    private fun transferToCast() {
        val startPos = _player.value?.currentPosition?.coerceAtLeast(0L) ?: 0L
        _player.value?.playWhenReady = false
        _isCasting.value = true
        loadCastMedia(fromPositionMs = startPos)
        _currentPlayer.value = castPlayer
    }

    @OptIn(UnstableApi::class)
    private fun loadCastMedia(fromPositionMs: Long) {
        val url = currentMediaUrl ?: return
        val remoteUrl = castManager.toLanUrl(url)
        if (remoteUrl == null) {
            _uiState.value = PlayerUiState.Error("Can't cast: no Wi-Fi network found to reach the TV.")
            return
        }
        val item = ExoMediaItem.Builder()
            .setUri(remoteUrl)
            .setMimeType(guessMimeType())
            .build()
        castPlayer?.apply {
            setMediaItem(item, fromPositionMs)
            playWhenReady = true
            prepare()
        }
    }

    @OptIn(UnstableApi::class)
    private fun transferToLocal() {
        val resumePos = castPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        _isCasting.value = false
        val exo = _player.value
        if (exo != null) {
            exo.seekTo(resumePos)
            exo.playWhenReady = true
        }
        _currentPlayer.value = exo
    }

    /** Best-effort MIME for the Cast receiver, inferred from the chosen source's title. */
    private fun guessMimeType(): String {
        val t = _currentSource.value?.title?.lowercase().orEmpty()
        return when {
            "mkv" in t -> "video/x-matroska"
            "webm" in t -> "video/webm"
            "avi" in t -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    private fun maybeSeekToResume(player: Player) {
        if (hasSeekedToResume) return
        hasSeekedToResume = true
        viewModelScope.launch {
            val saved = libraryRepository.getProgress(mediaId, mediaType, currentSeason, currentEpisode)
            if (saved != null && !saved.isFinished && saved.positionMs > 0) {
                player.seekTo(saved.positionMs)
            }
        }
    }

    private fun startProgressTicker() {
        progressTickJob?.cancel()
        progressTickJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                saveProgressNow()
                maybeWarmNextEpisode()
            }
        }
    }

    private fun saveProgressNow() {
        // Read from whichever local backend is active — ExoPlayer or the VLC fallback (both Media3
        // [Player]s). Keep using _player while casting so we persist the local position, not the TV's.
        val p: Player = _player.value ?: vlcPlayer ?: return
        val d = details ?: return
        val position = p.currentPosition
        if (position <= 0L) return
        // duration is C.TIME_UNSET (negative) until the container tail (mp4 moov / mkv cues) is
        // parsed — common on low-power TV that starts via the tail-grace path. Don't drop the save:
        // persist a 0 "unknown" duration so the resume point survives; the next ticker save fills in
        // the real duration once the player learns it. (This was the TV "forgot my spot" bug.)
        val rawDuration = p.duration
        val duration = if (rawDuration > 0L) rawDuration else 0L
        val item: MediaItem = d.item
        val progress = PlaybackProgress(
            mediaId = mediaId,
            mediaType = mediaType,
            season = currentSeason,
            episode = currentEpisode,
            positionMs = position,
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
            infoHash = activeInfoHash,
        )
        // Use cleanupScope so it still completes if invoked during teardown.
        cleanupScope.launch {
            runCatching { libraryRepository.saveProgress(item, progress) }
        }
    }

    private suspend fun stopActiveStream(removeFiles: Boolean) {
        streamJob?.cancel()
        streamJob = null
        activeInfoHash?.let { hash ->
            runCatching { torrentStreamer.stop(hash, removeFiles = removeFiles) }
        }
    }

    private fun buildTitle(d: MediaDetails): String {
        val base = d.item.title
        val s = currentSeason
        val e = currentEpisode
        return if (s != null && e != null) "$base · S${s}E${e}" else base
    }

    // --- Next-episode prefetch (TV only) ------------------------------------

    /** Warm the next episode when within [PREFETCH_LEAD_MS] / [PREFETCH_PCT] of the end. */
    private fun maybeWarmNextEpisode() {
        if (mediaType != MediaType.TV) return
        // No concurrent second-torrent download on a weak TV SoC — a full hash+disk+peer load during
        // playback is a direct freeze trigger. Cost is only a buffering spinner between episodes.
        if (deviceProfile.isLowPower) return
        if (prefetchTriggeredForEpisode) return
        if (_isCasting.value) return                 // remote playback: a phone-side head buffer is useless
        if (!isOnUnmeteredNetwork()) return
        val exo = _player.value ?: return
        val duration = exo.duration
        val position = exo.currentPosition
        if (duration <= 0L || position < 0L) return

        val remainingMs = duration - position
        val pctDone = position.toFloat() / duration
        if (remainingMs !in 0..PREFETCH_LEAD_MS && pctDone < PREFETCH_PCT) return

        prefetchTriggeredForEpisode = true           // claim the slot before the await
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch { warmEpisode() }
    }

    private suspend fun warmEpisode() {
        val d = details ?: return
        val s = currentSeason ?: return
        val e = currentEpisode ?: return
        val (nextSeason, nextEpisode) = nextEpisodeCoords(d, s, e) ?: return

        val sources = when (val r = sourceRepository.resolve(d, nextSeason, nextEpisode)) {
            is DataResult.Success -> r.data
            is DataResult.Error -> return
        }
        if (sources.isEmpty()) return
        val best = pickPreferred(sources, networkQualityPreference())

        val playing = activeInfoHash
        if (best.infoHash == playing) return
        if (best.infoHash in torrentStreamer.cachedTorrents()) {
            prefetchedInfoHash = best.infoHash
            return
        }
        prefetchedInfoHash = torrentStreamer.prefetch(best, setOfNotNull(playing))
    }

    /** Next-episode coordinates: same-season next, else episode 1 of the next real season. */
    private suspend fun nextEpisodeCoords(
        d: MediaDetails,
        curSeason: Int,
        curEpisode: Int,
    ): Pair<Int, Int>? {
        val curCount = when (val r = catalogRepository.getEpisodes(mediaId, curSeason)) {
            is DataResult.Success -> r.data.size
            is DataResult.Error -> return null
        }
        if (curEpisode < curCount) return curSeason to (curEpisode + 1)

        val nextSeason = d.seasons
            .map { it.seasonNumber }
            .filter { it > curSeason && it >= 1 }
            .minOrNull() ?: return null
        val nextCount = when (val r = catalogRepository.getEpisodes(mediaId, nextSeason)) {
            is DataResult.Success -> r.data.size
            is DataResult.Error -> return null
        }
        if (nextCount <= 0) return null
        return nextSeason to 1
    }

    /** Previous-episode coordinates: same-season -1, else the last episode of the previous real season. */
    private suspend fun previousEpisodeCoords(
        d: MediaDetails,
        curSeason: Int,
        curEpisode: Int,
    ): Pair<Int, Int>? {
        if (curEpisode > 1) return curSeason to (curEpisode - 1)

        val prevSeason = d.seasons
            .map { it.seasonNumber }
            .filter { it < curSeason && it >= 1 }
            .maxOrNull() ?: return null
        val prevCount = when (val r = catalogRepository.getEpisodes(mediaId, prevSeason)) {
            is DataResult.Success -> r.data.size
            is DataResult.Error -> return null
        }
        if (prevCount <= 0) return null
        return prevSeason to prevCount
    }

    // --- In-player episode navigation (TV only) -----------------------------

    /** Load the current season's episode list and refresh next/previous availability. */
    private suspend fun loadEpisodeList() {
        val s = currentSeason ?: return   // movies have no episode list
        when (val r = catalogRepository.getEpisodes(mediaId, s)) {
            is DataResult.Success -> _episodes.value = r.data
            is DataResult.Error -> _episodes.value = emptyList()
        }
        refreshNavAvailability()
    }

    private suspend fun refreshNavAvailability() {
        val d = details
        val s = currentSeason
        val e = currentEpisode
        if (d == null || s == null || e == null) {
            _hasNext.value = false
            _hasPrevious.value = false
            return
        }
        _hasNext.value = nextEpisodeCoords(d, s, e) != null
        _hasPrevious.value = previousEpisodeCoords(d, s, e) != null
    }

    /**
     * Switch playback to a specific episode of this series: save the current spot, tear down the
     * active stream, re-resolve sources for the target episode and start it — mirroring [load]'s
     * resolve+start path (reset resume seek, reload subtitles, rebuild ExoPlayer via [startSource]).
     */
    fun playEpisode(season: Int, episode: Int) {
        if (mediaType != MediaType.TV) return
        val d = details ?: return
        if (season == currentSeason && episode == currentEpisode) return

        viewModelScope.launch {
            // Persist where we are in the OUTGOING episode before switching coords.
            saveProgressNow()
            bufferWatchdogJob?.cancel()
            stopActiveStream(removeFiles = false)
            prefetchJob?.cancel()
            prefetchedInfoHash = null

            currentSeason = season
            currentEpisode = episode
            _currentSeasonNumber.value = season
            _currentEpisodeNumber.value = episode
            hasSeekedToResume = false

            _title.value = buildTitle(d)
            _uiState.value = PlayerUiState.Buffering(0, 0, 0, "Loading…")

            // Refresh the season's episode list if we crossed a season boundary, and nav availability.
            if (season != (_episodes.value.firstOrNull()?.seasonNumber)) {
                loadEpisodeList()
            } else {
                refreshNavAvailability()
            }

            // Reload subtitles for the new episode.
            viewModelScope.launch { loadSubtitles(d) }

            _uiState.value = PlayerUiState.Buffering(0, 0, 0, "Finding sources…")
            val list = when (val r = sourceRepository.resolve(d, currentSeason, currentEpisode)) {
                is DataResult.Success -> r.data
                is DataResult.Error -> {
                    _uiState.value = PlayerUiState.Error(r.message)
                    return@launch
                }
            }
            if (list.isEmpty()) {
                _uiState.value = PlayerUiState.Error("No streamable sources found.")
                return@launch
            }
            _sources.value = list.sortedByDescending { it.rank }
            // Fresh episode -> fresh failover budget over the new candidate list.
            triedInfoHashes.clear()
            failoverCount = 0
            val best = pickPreferred(list, networkQualityPreference())
            startSource(best)
        }
    }

    /** Advance to the next episode (same-season next, else episode 1 of the next real season). */
    fun nextEpisode() {
        val d = details ?: return
        val s = currentSeason ?: return
        val e = currentEpisode ?: return
        viewModelScope.launch {
            val (ns, ne) = nextEpisodeCoords(d, s, e) ?: return@launch
            playEpisode(ns, ne)
        }
    }

    /** Go to the previous episode (same-season -1, else last episode of the previous real season). */
    fun previousEpisode() {
        val d = details ?: return
        val s = currentSeason ?: return
        val e = currentEpisode ?: return
        viewModelScope.launch {
            val (ps, pe) = previousEpisodeCoords(d, s, e) ?: return@launch
            playEpisode(ps, pe)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Save final progress, release the player, and stop the torrent — keeping the
        // partial download cached (removeFiles = false) for fast resume next time.
        saveProgressNow()
        progressTickJob?.cancel()
        streamJob?.cancel()
        bufferWatchdogJob?.cancel()
        prefetchJob?.cancel() // warmed next-episode torrent stays paused + cached intentionally
        val exo = _player.value
        playerListener?.let { exo?.removeListener(it) }
        exo?.release()
        _player.value = null
        releaseVlcPlayer()
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        _currentPlayer.value = null
        val hash = activeInfoHash
        if (hash != null) {
            cleanupScope.launch {
                runCatching { torrentStreamer.stop(hash, removeFiles = false) }
            }
        }
    }

    private companion object {
        /** Auto-pick floor: don't choose a torrent under this many seeders if a healthier one exists. */
        const val MIN_SEEDERS = 8

        /** ExoPlayer's own per-load retry count — keeps re-requesting a not-yet-downloaded range. */
        const val SOURCE_LOAD_RETRIES = 12
        /** Backstop: re-prepare the player up to this many times after a load gives up entirely. */
        const val MAX_SOURCE_RETRIES = 8
        const val SOURCE_RETRY_BACKOFF_MS = 1_500L

        /** Cap automatic source failovers per title so a wholly-dead title can't loop forever. */
        const val MAX_FAILOVERS = 5
        /** Pre-player buffering wall-clock before the watchdog will consider a source a failure. */
        const val FAILOVER_BUFFER_BUDGET_MS = 45_000L
        /** Watchdog poll cadence while waiting for a source to become playable. */
        const val WATCHDOG_TICK_MS = 3_000L
        /** Head bytes flat this long (while still not playing) = stalled swarm -> fail over. */
        const val WATCHDOG_STALL_MS = 20_000L
        const val PROGRESS_INTERVAL_MS = 10_000L
        /** Start warming the next episode when this close to the end (~3 min). */
        const val PREFETCH_LEAD_MS = 3 * 60_000L
        /** ...or once past this fraction of the runtime, whichever comes first. */
        const val PREFETCH_PCT = 0.85f

        /** Bytes that must download before playback can actually START (head + a moov/tail cushion) —
         *  the ETA target. Bigger than the bare head so the estimate isn't wildly optimistic. */
        const val PLAYABLE_TARGET_BYTES = 6L * 1024 * 1024
        /** Fixed seconds added to the ETA for the moov/tail fetch + ExoPlayer prepare AFTER the head
         *  fills, so "time to playing" includes the post-download buffering, not just the download. */
        const val PREPARE_MARGIN_SECONDS = 3
        /** Suggest a smaller stream only after a buffer has dragged on this long (~25 s). */
        const val BUFFERING_NAG_MS = 25_000L
        /** ...and only while the download rate is below this (~150 KB/s) — a genuinely starved swarm. */
        const val BUFFERING_LOW_RATE_BYTES = 150 * 1024
    }
}
