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

            // Auto-pick the best source within the user's per-network quality cap.
            val best = pickPreferred(list, networkQualityPreference())
            startSource(best)
        }
    }

    /** Switch to a different quality/source, keeping the previous download cached. */
    fun selectSource(source: StreamSource) {
        if (source.infoHash == _currentSource.value?.infoHash) return
        viewModelScope.launch {
            // Persist where we are before tearing the current stream down.
            saveProgressNow()
            stopActiveStream(removeFiles = false)
            startSource(source)
        }
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
        val effectiveTier =
            if (deviceProfile.isLowPower) minOf(pref.maxTier, QualityPreference.FHD_1080.maxTier)
            else pref.maxTier
        val capped = list.filter { QualityPreference.tierOf(it.quality) <= effectiveTier }.ifEmpty { list }
        val healthy = capped.filter { (it.seeders ?: 0) >= MIN_SEEDERS }.ifEmpty { capped }
        // Within the (resolution-capped, well-seeded) set, the size preference decides where on the
        // size/bitrate range to land — a 1080p episode can be 700 MB or 4 GB.
        val picked = when (sizePref) {
            StreamSizePreference.HIGHEST ->
                // Best quality: most seeders, then the LARGEST file (highest bitrate).
                healthy.maxWithOrNull(compareBy<StreamSource>({ it.seeders ?: 0 }, { it.sizeBytes ?: 0L }))
            StreamSizePreference.SMALLEST -> {
                // Leanest well-seeded copy: smallest known size, ties to more seeders.
                val withSize = healthy.filter { (it.sizeBytes ?: 0L) > 0L }
                if (withSize.isNotEmpty()) {
                    withSize.minWithOrNull(
                        compareBy<StreamSource> { it.sizeBytes ?: 0L }.thenByDescending { it.seeders ?: 0 },
                    )
                } else {
                    healthy.maxByOrNull { it.seeders ?: 0 }
                }
            }
            StreamSizePreference.BALANCED -> {
                // Drop the biggest ~third (remux/bluray outliers), then take the best-seeded of the
                // rest (smaller on a tie) — lean but not over-compressed.
                val withSize = healthy.filter { (it.sizeBytes ?: 0L) > 0L }.sortedBy { it.sizeBytes ?: 0L }
                val pool = if (withSize.size >= 4) withSize.take((withSize.size * 2 + 2) / 3) else healthy
                pool.minWithOrNull(
                    compareByDescending<StreamSource> { it.seeders ?: 0 }.thenBy { it.sizeBytes ?: Long.MAX_VALUE },
                )
            }
        }
        return picked ?: list.first()
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
        prefetchTriggeredForEpisode = false
        prefetchJob?.cancel()
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
    }

    private fun handleStatus(status: StreamStatus, source: StreamSource) {
        val url = status.streamUrl
        if (url != null) {
            ensurePlayer(url)
            if (status.state == StreamState.ERROR && status.errorMessage != null) {
                _uiState.value = PlayerUiState.Error(status.errorMessage!!)
            }
            return
        }

        if (status.state == StreamState.ERROR) {
            _uiState.value = PlayerUiState.Error(status.errorMessage ?: "Streaming failed.")
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
        )
    }

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(url: String) {
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
                        _uiState.value = PlayerUiState.Playing
                    }
                    Player.STATE_BUFFERING -> {
                        if (_uiState.value !is PlayerUiState.Playing) {
                            val s = _currentSource.value
                            _uiState.value = PlayerUiState.Buffering(
                                percent = 100,
                                seeders = s?.seeders ?: 0,
                                downloadRateBytes = 0,
                                label = "Buffering…",
                            )
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
                // ERROR_CODE_IO_* (2000-2999) on a torrent = pieces aren't downloaded YET, NOT a dead
                // stream. Back off to let them arrive, then re-prepare (ExoPlayer keeps the position)
                // and keep playing. Only show the error screen once retries are genuinely exhausted
                // (e.g. a 0-seeder torrent that will never fill).
                val transient = error.errorCode in 2000..2999
                if (transient && !_isCasting.value && p != null && sourceErrorRetries < MAX_SOURCE_RETRIES) {
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
                } else {
                    _uiState.value = PlayerUiState.Error(error.localizedMessage ?: "Playback error.")
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

    private fun maybeSeekToResume(exo: ExoPlayer) {
        if (hasSeekedToResume) return
        hasSeekedToResume = true
        viewModelScope.launch {
            val saved = libraryRepository.getProgress(mediaId, mediaType, currentSeason, currentEpisode)
            if (saved != null && !saved.isFinished && saved.positionMs > 0) {
                exo.seekTo(saved.positionMs)
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
        val exo = _player.value ?: return
        val d = details ?: return
        val position = exo.currentPosition
        if (position <= 0L) return
        // exo.duration is C.TIME_UNSET (negative) until the container tail (mp4 moov / mkv cues) is
        // parsed — common on low-power TV that starts via the tail-grace path. Don't drop the save:
        // persist a 0 "unknown" duration so the resume point survives; the next ticker save fills in
        // the real duration once ExoPlayer learns it. (This was the TV "forgot my spot" bug.)
        val rawDuration = exo.duration
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
        prefetchJob?.cancel() // warmed next-episode torrent stays paused + cached intentionally
        val exo = _player.value
        playerListener?.let { exo?.removeListener(it) }
        exo?.release()
        _player.value = null
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
        const val PROGRESS_INTERVAL_MS = 10_000L
        /** Start warming the next episode when this close to the end (~3 min). */
        const val PREFETCH_LEAD_MS = 3 * 60_000L
        /** ...or once past this fraction of the runtime, whichever comes first. */
        const val PREFETCH_PCT = 0.85f
    }
}
