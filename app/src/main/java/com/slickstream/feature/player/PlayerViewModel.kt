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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
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
import com.slickstream.core.model.hasAired
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.StartupBlocker
import com.slickstream.core.model.StreamSource
import com.slickstream.data.torrent.TransferAccounting
import com.slickstream.core.model.StreamState
import com.slickstream.core.model.StreamStatus
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.SourceRepository
import com.slickstream.core.repository.TorrentStreamer
import com.slickstream.core.model.SubtitleTrack
import com.slickstream.data.download.DownloadManager
import com.slickstream.data.settings.AudioLanguage
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
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
        /** Bytes-becoming-file per second — the honest speed to print. NOT libtorrent's wire rate,
         *  which also counts protocol overhead and the duplicate blocks the swarm discards. */
        val payloadRateBytes: Int,
        val label: String,
        /** Rough seconds until playback can start (head buffer / rate); null when not estimable. */
        val etaSeconds: Int? = null,
    ) : PlayerUiState

    /** Player has a stream URL and is (or will shortly be) playing. */
    data object Playing : PlayerUiState

    data class Error(val message: String) : PlayerUiState
}

/**
 * A mid-playback stall: the player drained its buffer faster than the torrent could refill it, so
 * playback paused itself to re-buffer. The (frozen) video frame stays on screen underneath — this
 * just drives a small "buffering ~Xs" badge so it never looks permanently frozen. Distinct from the
 * full-screen startup [PlayerUiState.Buffering]; the UI stays in [PlayerUiState.Playing] throughout.
 */
data class RebufferState(
    /** Best-effort seconds until enough is buffered to resume, or null when not estimable. */
    val etaSeconds: Int?,
    /** Display speed: bytes becoming file per second (see [PlayerUiState.Buffering]). */
    val payloadRateBytes: Int,
)

/**
 * One selectable audio track for the in-player audio picker.
 *
 * [id] is backend-scoped: "groupIndex:trackIndex" over the ExoPlayer audio track groups, or
 * "vlc:<esId>" for a libVLC elementary stream. [PlayerViewModel.selectAudioTrack] routes on the prefix,
 * so a stale id from the other backend can never be applied to the wrong player.
 *
 * [label] comes from [AudioTrackChoice.describe] and is guaranteed to DISTINGUISH the tracks — the old
 * `Format.label ?: languageName` rendered three identical "Unknown" rows on an untagged MULTI file.
 */
data class AudioTrackOption(
    val id: String,
    /** Resolved ISO-639-1 code ("en", "zh"), or "" when the track identifies no language at all. */
    val language: String,
    val label: String,
    val selected: Boolean,
)

/** Subtitle appearance the players apply to their Media3 SubtitleView. */
data class CaptionPrefs(val size: SubtitleSize, val style: SubtitleStyle)

/** Live swarm/transfer snapshot shown under the player's chunk bar. */
data class StreamStats(
    val seeders: Int,
    val peers: Int,
    /** WIRE rate. Kept because the chunk bar's "stalled" verdict must key on whether the link is moving
     *  AT ALL: a non-zero wire rate with zero payload means peers are still talking to us, which is not
     *  a dead swarm and must not be labelled one. */
    val downloadRateBytes: Int,
    /** ...and the rate it PRINTS: bytes that became file. */
    val payloadRateBytes: Int = 0,
    /** Whole-percent of accounted traffic thrown away (hash-failed + redundant duplicate blocks), or
     *  null when it is too small / too early to be worth saying. This is bandwidth the bounded TV link
     *  spent on nothing, taken out of the read-ahead window — so it belongs on the diagnostic line
     *  rather than silently inflating the speed. */
    val wastedPercent: Int? = null,
    val progress: Float,
    /** The next-episode warm is running right now (resolving sources / filling its head + index). */
    val precaching: Boolean = false,
    /** The next-episode warm FINISHED: its head and container index are on disk, so pressing "Up next"
     *  starts in about a second. Distinct from [precaching] because "is it warming" and "is it warm"
     *  are the two different questions a user staring at this line is actually asking. */
    val nextEpisodeReady: Boolean = false,
    /** Hash-verifying cached/on-disk data (resume), not downloading — rate/seeders read 0 but it's fine. */
    val isChecking: Boolean = false,
    /** What the readiness gate is still waiting for, verbatim from the engine. The chunk bar renders
     *  its verdict from THIS while starting up instead of guessing from the fill pattern — that guess
     *  is what produced "solid teal head, yet 'low buffer'". */
    val startupBlocker: StartupBlocker = StartupBlocker.NONE,
    /** Media duration, so the chunk bar can express the downloaded lead in SECONDS OF VIDEO rather than
     *  as a fraction of the file (4% of a 10 GB movie is 400 MB — a nonsense bar for "healthy"). 0 when
     *  not known yet (pre-prepare), where the bar falls back to the fraction heuristic. */
    val durationMs: Long = 0L,
)

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
    private val downloadManager: DownloadManager,
    private val diagnostics: com.slickstream.core.diagnostics.Diagnostics,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Compact, secret-free source descriptor for diagnostics breadcrumbs. */
    private fun StreamSource?.diagTag(): String = when {
        this == null -> "none"
        isOffline -> "offline"
        isDirect -> "direct/${quality}"
        else -> "torrent/${quality}"
    }

    // --- Nav args -----------------------------------------------------------
    private val mediaType: MediaType =
        MediaType.fromName(savedStateHandle.get<String>(NavArg.MEDIA_TYPE))
    private val mediaId: Int = savedStateHandle.get<Int>(NavArg.MEDIA_ID) ?: -1
    private val navSeason: Int? =
        savedStateHandle.get<Int>(NavArg.SEASON)?.takeIf { it >= 0 }
    private val navEpisode: Int? =
        savedStateHandle.get<Int>(NavArg.EPISODE)?.takeIf { it >= 0 }
    /** Explicit movie action from details. This is intentionally a nav argument (not inferred from
     *  progress) so Resume and Start from beginning remain distinct across process recreation. */
    private val startOverRequested: Boolean = savedStateHandle.get<Boolean>(NavArg.START_OVER) == true

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

    /**
     * Non-null while playback is stalled mid-stream and re-buffering. Drives the small "buffering ~Xs"
     * badge over the (still-Playing) video. Set by the player listener on STATE_BUFFERING-while-Playing,
     * refreshed live from the torrent feed, cleared on STATE_READY / source switch.
     */
    private val _rebuffering = MutableStateFlow<RebufferState?>(null)
    val rebuffering: StateFlow<RebufferState?> = _rebuffering.asStateFlow()

    /** Latest torrent status, kept fresh even while Playing so the rebuffer ETA can be recomputed. */
    private var latestStatus: StreamStatus? = null

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    /** The active player surface the UI binds to — local ExoPlayer or the remote CastPlayer. */
    private val _currentPlayer = MutableStateFlow<androidx.media3.common.Player?>(null)
    val currentPlayer: StateFlow<androidx.media3.common.Player?> = _currentPlayer.asStateFlow()

    // First-frame guard: a source can report STATE_READY (uiState=Playing) yet never paint a frame — a
    // botched surface hand-off on a mid-stream failover leaves AUDIO over a BLACK screen. onVideoSizeChanged
    // (w>0,h>0) is the "a frame actually decoded" signal for BOTH backends. If it never fires, the watchdog
    // forces the source panel open so the user always has an escape hatch (never stranded on black).
    @Volatile private var firstFrameRendered = false
    private var firstFrameWatchdogJob: Job? = null

    /** One-shot per source: has the foreign-audio check (onTracksChanged) already run? */
    private var foreignAudioChecked = false
    private val _forceSourcePanel = MutableStateFlow(false)
    /** Set true when a fresh source reaches "Playing" but never renders a frame — the UI opens the source
     *  picker in response (an escape hatch that doesn't depend on D-pad focus). UI clears it via [consumeForceSourcePanel]. */
    val forceSourcePanel: StateFlow<Boolean> = _forceSourcePanel.asStateFlow()

    /** UI acknowledges it opened the source panel for [forceSourcePanel]. */
    fun consumeForceSourcePanel() { _forceSourcePanel.value = false }

    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _title = MutableStateFlow("Now Playing")
    val title: StateFlow<String> = _title.asStateFlow()

    /** Backdrop (or poster) art for the title, shown behind the buffering overlay so the wait feels
     *  like the movie loading instead of a black void — dissolves into the real first frame on play. */
    private val _backdropUrl = MutableStateFlow<String?>(null)
    val backdropUrl: StateFlow<String?> = _backdropUrl.asStateFlow()

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

    /** Metadata (title + still) for the NEXT episode, resolved on load / episode switch so the player
     *  can show an "Up next" card near the end. Null for movies or at the series finale. */
    private val _upNextEpisode = MutableStateFlow<Episode?>(null)
    val upNextEpisode: StateFlow<Episode?> = _upNextEpisode.asStateFlow()

    /** True for a movie (no episodes) — drives the end-of-movie "similar titles" bar instead of the
     *  next-episode card. */
    val isMovie: Boolean get() = mediaType == MediaType.MOVIE

    /** Similar movies (TMDB) for the end-of-movie autoplay bar; empty for TV or until loaded. */
    private val _similarMovies = MutableStateFlow<List<MediaItem>>(emptyList())
    val similarMovies: StateFlow<List<MediaItem>> = _similarMovies.asStateFlow()

    // --- Internal -----------------------------------------------------------
    private var details: MediaDetails? = null
    private var streamJob: Job? = null

    /** In-flight pre-play validation of a direct source. Cancelled on every new startSource so a
     *  stale probe can't rebind its old URL (or fail over) after the user already switched source. */
    private var directValidationJob: Job? = null
    private var progressTickJob: Job? = null
    private var activeInfoHash: String? = null
    private var hasSeekedToResume = false
    /** Non-null for a Start-from-beginning player session. It begins at zero and tracks the live
     *  position across source failovers, so the OLD persisted resume point can never leak back in while
     *  a failover after real playback still resumes this NEW session at the correct position. */
    private var startOverSessionPositionMs: Long? = if (startOverRequested) 0L else null
    /** True while hopping episodes: suppresses progress saves so the OUTGOING episode's position can't
     *  be written under the INCOMING episode's key (the "next episode opens on the end credits" bug).
     *  Cleared once the new media item is bound to the (reused) player. */
    @Volatile private var switchingEpisode = false
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
    /** Automatic TORRENT failovers since the last fresh load()/episode switch; capped (expensive). */
    private var failoverCount = 0
    /** Automatic DIRECT (RD/file-server) failovers — separate, generous budget: RD lists many dead/
     *  removed/fake [RD+] links that only fail at play time, and skipping them is instant + free. */
    private var directFailoverCount = 0
    /** True from the instant ANY backend transition starts (failover, Exo->VLC switch, manual
     *  selectSource, short-fake failover) until the new source's startSource() runs. EVERY transition
     *  trigger claims it via [beginTransition] FIRST, so two can't interleave within one main-loop drain
     *  and leave the player half-Exo-half-VLC or double-release it. Reset only in startSource(). */
    private var transitionInFlight = false

    /** Claim the single transition slot. Returns false if a transition is already running (the caller
     *  MUST bail). All on the main thread, so a plain flag is correct — no lock/atomic needed. */
    private fun beginTransition(): Boolean {
        if (transitionInFlight) return false
        transitionInFlight = true
        return true
    }
    /** Watchdog that fails over when a source never reaches playable (dead / fake-seeded swarm). */
    private var bufferWatchdogJob: Job? = null
    /** Post-playback watchdog: if a mid-stream stall persists (a source whose seeders can't sustain the
     *  bitrate — the "plays 10 min then buffers forever" case), auto-downshift to a smaller source. */
    private var rebufferWatchdogJob: Job? = null
    /** True once this source has actually reached playback — gates the downshift watchdog so it can't
     *  fight the STARTUP failover watchdog (which owns the pre-first-frame phase). Reset per source. */
    @Volatile private var hasReachedPlaying = false
    /** Last contiguous head byte count emitted — scattered/tail pieces must not mask a stuck head. */
    @Volatile private var lastEmittedDownloadedBytes: Long = 0L

    // --- "Stuck buffering -> try a smaller stream" hint ---
    /** When the current continuous buffer began (uptime ms); 0 while not buffering. */
    private var bufferingSinceMs: Long = 0L
    /** When the streamer first emitted READY for THIS source (uptime ms); 0 until then. Marks the point
     *  after which the player HAS a URL and is trying to reach first frame — so a long buffer past it,
     *  while the torrent is still alive, means the source can't decode (e.g. missing mp4 moov) and we
     *  should fail over rather than grind tens-of-% into the download. Reset on every fresh source. */
    @Volatile private var firstReadyAtMs: Long = 0L
    /** Already shown the smaller-stream hint for this source — don't nag again. */
    private var suggestedSmallerForSource = false
    /** Effective quality cap from the last auto-pick — reused by the synchronous smaller-source scan. */
    @Volatile private var currentNetworkMaxTier: Int? = null

    // --- Next-episode prefetch (see [NextEpisodeWarm] for the policy) ---
    private var prefetchJob: Job? = null

    /** The episode we already fired a warm FOR — keyed by (season, episode) rather than a bare boolean.
     *  The boolean was reset by startSource() on EVERY source start, so a mid-episode failover or manual
     *  source switch killed an in-flight warm and burned its one-shot slot; and a warm that failed could
     *  never be retried for the rest of the episode. */
    private var warmAttemptedFor: Pair<Int, Int>? = null

    /** Backs off a retry after a failed warm (dead magnet / no sources) instead of re-running the whole
     *  addon fan-out on every 10 s tick. */
    private var warmRetryAtMs: Long = 0L

    /** The release the warm pinned, keyed to the episode it was warmed for. */
    @Volatile private var warmed: NextEpisodeWarm.Warmed? = null

    /** What the chunk-bar info line should say about the warm. */
    private enum class WarmPhase { IDLE, WARMING, READY }
    @Volatile private var warmPhase: WarmPhase = WarmPhase.IDLE

    // --- PiP: current video aspect ratio for PictureInPictureParams (clamped at use site) ---
    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    // --- Scrub-bar frame previews (sparse thumbnails sampled across the timeline) ---
    private val thumbnails = FrameThumbnailExtractor(torrentStreamer)
    /** Bumps when a new preview thumbnail lands, so the scrub UI recomposes. */
    val thumbnailVersion: StateFlow<Int> = thumbnails.version
    /** Nearest decoded preview frame to [positionMs], or null when that slice isn't sampled yet. */
    fun thumbnailAt(positionMs: Long): android.graphics.Bitmap? = thumbnails.thumbnailAt(positionMs)

    /** Per-bucket download fill (0f..1f) across the file for the player's chunk bar; empty when N/A. */
    fun pieceMap(buckets: Int): FloatArray =
        activeInfoHash?.let { torrentStreamer.pieceMap(it, buckets) } ?: FloatArray(0)

    /** Live swarm/transfer stats for the chunk-bar info row, or null when not torrent-backed. */
    fun streamStats(): StreamStats? = latestStatus?.let {
        StreamStats(
            seeders = it.seeders,
            peers = it.peers,
            downloadRateBytes = it.downloadRateBytes,
            payloadRateBytes = it.payloadRateBytes,
            wastedPercent = TransferAccounting.wastedPercentToShow(it.payloadBytes, it.wastedBytes),
            progress = it.progress,
            // Report the WARM's own phase, not `prefetchJob.isActive`. The job stays active through the
            // resolve fan-out and goes inactive the instant the head lands, so the old flag said
            // "caching next episode" during the part where nothing was downloading yet and said nothing
            // at all once the precache was actually ready.
            precaching = warmPhase == WarmPhase.WARMING,
            nextEpisodeReady = warmPhase == WarmPhase.READY,
            isChecking = it.isChecking,
            startupBlocker = it.startupBlocker,
            durationMs = _currentPlayer.value?.duration?.takeIf { d -> d > 0 } ?: 0L,
        )
    }

    // --- Subtitles ---
    private val _subtitles = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitles: StateFlow<List<SubtitleTrack>> = _subtitles.asStateFlow()
    private val _currentSubtitle = MutableStateFlow<SubtitleTrack?>(null)
    val currentSubtitle: StateFlow<SubtitleTrack?> = _currentSubtitle.asStateFlow()

    // --- Audio tracks -------------------------------------------------------------------------
    // Populated by BOTH backends (ExoPlayer's onTracksChanged and libVLC's ESAdded/ESSelected), so the
    // one picker the UI renders works whichever player is running. It used to be written only by the
    // ExoPlayer listener, which is why a source that routed to libVLC showed NO audio button at all
    // while VLC was happily decoding three tracks.
    private val _audioTracks = MutableStateFlow<List<AudioTrackOption>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrackOption>> = _audioTracks.asStateFlow()

    /** The track playing RIGHT NOW, as the transport chip shows it ("English · AC3 · 5.1"), or null
     *  before any backend has reported. Nothing outside the picker used to say what you were hearing. */
    private val _currentAudio = MutableStateFlow<AudioTrackOption?>(null)
    val currentAudio: StateFlow<AudioTrackOption?> = _currentAudio.asStateFlow()

    /** True when the playing track is NOT provably the user's preferred language — either it declares
     *  a different one, or it declares nothing at all. Drives the amber tint on the audio button and
     *  the "we couldn't confirm the language" line in the picker. Deliberately honest: "we can't tell"
     *  counts as needing attention, because that is exactly the case that played Chinese silently. */
    private val _audioNeedsAttention = MutableStateFlow(false)
    val audioNeedsAttention: StateFlow<Boolean> = _audioNeedsAttention.asStateFlow()

    /** Preferred SPOKEN language (ISO-639-1), from settings. Applied to both backends — it was a
     *  hardcoded "en" in the ExoPlayer builder that the VLC path never saw. */
    private val audioLanguage: StateFlow<String> = settingsRepository.settings
        .map { it.audioLanguage.code }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            AudioLanguage.DEFAULT.code,
        )
    private val preferredAudioCode: String get() = audioLanguage.value

    /** One automatic audio decision per source. Re-armed in startSource and on an Exo->VLC handoff
     *  (the new backend must decide again), and stood down the moment the user picks by hand. */
    private var audioDecisionMade = false
    /** How many audio ES the last VLC decision was made from. libVLC announces streams one at a time,
     *  so a decision taken at 2 tracks must be revisited when the 3rd arrives. */
    private var audioDecidedTrackCount = 0

    /** The user chose a track by hand for this source. Suppresses [_audioNeedsAttention] for the rest
     *  of it — re-flagging a track they deliberately selected (because it declares no language) would
     *  turn an honest warning into a nag. */
    private var audioPickedByUser = false

    /** Latest ExoPlayer Tracks, kept so selectAudioTrack can build a precise per-track override. */
    private var latestTracks: androidx.media3.common.Tracks? = null
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

    /** End-of-content thresholds (0f..1f): (Up-next card for episodes, similar-titles bar for movies). */
    val endThresholds: StateFlow<Pair<Float, Float>> = settingsRepository.settings
        .map { (it.upNextPercent / 100f) to (it.movieBarPercent / 100f) }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            0.95f to 0.97f,
        )

    /** Persist subtitle size system-wide (DataStore) straight from the player's subtitle menu;
     *  [captionPrefs] re-emits and both players re-apply it live, and it sticks for next time. */
    fun setSubtitleSize(size: SubtitleSize) {
        viewModelScope.launch { settingsRepository.setSubtitleSize(size) }
    }

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

    /** Fetch TMDB "similar" titles for the end-of-content suggestion bar — similar films at the end of a
     *  movie, or similar shows at the end of the available series (non-critical; ignore failures). */
    private suspend fun loadSimilarMovies() {
        when (val r = catalogRepository.getSimilar(mediaId, mediaType)) {
            is DataResult.Success -> _similarMovies.value = r.data.filter { it.posterUrl != null }.take(12)
            is DataResult.Error -> Unit
        }
    }

    private fun load() {
        viewModelScope.launch {
            // OFFLINE FIRST — before ANY network call. If this exact title/episode is fully downloaded
            // AND the file is on disk, play it straight from local storage. This MUST precede
            // getDetails(): on a plane getDetails() fails ("Can't reach TMDB") and would return before we
            // ever checked for the download. The download row carries title/poster, so no TMDB needed.
            _uiState.value = PlayerUiState.Buffering(0, 0, 0, "Loading…")
            val completed = downloadManager.completedDownload(mediaId, mediaType, currentSeason, currentEpisode)
            // Revalidate the file is actually on disk AND roughly the expected size — a truncated/partial
            // file (interrupted copy, cleared cache) must fall through to online resolution, not play junk.
            val offlineFile = completed?.filePath?.let { java.io.File(it) }
                ?.takeIf { it.exists() && (completed.totalBytes <= 0 || it.length() >= completed.totalBytes * 0.99) }
            if (completed != null && offlineFile != null) {
                _title.value = buildOfflineTitle(completed)
                _backdropUrl.value = completed.backdropUrl ?: completed.posterUrl
                // Best-effort online extras — episode list / similar / subtitles. Each fails silently
                // offline; playback does not depend on any of them.
                viewModelScope.launch { runCatching { loadEpisodeList() } }
                val offline = StreamSource(
                    title = "Downloaded",
                    magnetUri = "",
                    infoHash = "offline:$mediaId:${currentSeason ?: -1}:${currentEpisode ?: -1}",
                    quality = completed.quality,
                    sizeBytes = offlineFile.length(),
                    seeders = null,
                    provider = "Downloads",
                    directUrl = android.net.Uri.fromFile(offlineFile).toString(),
                    isOffline = true,
                )
                _sources.value = listOf(offline)
                triedInfoHashes.clear()
                failoverCount = 0
                startSource(offline)
                return@launch
            }

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
            _backdropUrl.value = d.item.backdropUrl ?: d.item.posterUrl
            viewModelScope.launch { loadSubtitles(d) }
            viewModelScope.launch { loadEpisodeList() }
            if (isMovie) viewModelScope.launch { loadSimilarMovies() }

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
            // DIRECT (file-server) sources first, then torrents by health — mirrors the repository's
            // order. A plain sortedByDescending { rank } buried direct sources below every seeded torrent
            // (directs have null seeders -> rank ≈ quality only), so the user "never saw the HTTPS
            // options". This only orders the PICKER list; the auto-pick uses its own file-server-first
            // logic (pickResumeOrPreferred), so rank-based floors there are untouched.
            _sources.value = list.sortedWith(
                compareByDescending<StreamSource> { it.isDirect }.thenByDescending { it.rank }
            )
            // Fresh title -> fresh failover budget over the new candidate list.
            triedInfoHashes.clear()
            failoverCount = 0
            directFailoverCount = 0

            // Start-over deliberately ignores the old release as well as its old timestamp. Resume may
            // reuse an exact release only when it is actually cached on THIS device and still satisfies
            // this TV's quality/decoder gates.
            val best = if (startOverRequested) {
                diagnostics.breadcrumb("player startOver=true")
                pickPreferred(list, networkQualityPreference())
            } else {
                pickResumeOrPreferred(list, currentSeason, currentEpisode)
            }
            startSource(best)
        }
    }

    /** Switch to a different quality/source, keeping the previous download cached. */
    fun selectSource(source: StreamSource) {
        if (source.infoHash == _currentSource.value?.infoHash) return
        // Claim the single transition slot so a manual switch can't race an in-flight auto-failover /
        // VLC switch and double-tear-down the player (a real crash path). startSource() releases it.
        if (!beginTransition()) return
        // A source switch clears any pending hint; startSource() re-arms it for the new source.
        _suggestSmaller.value = false
        viewModelScope.launch {
            // Persist where we are before tearing the current stream down.
            saveProgressNow()
            bufferWatchdogJob?.cancel()
            rebufferWatchdogJob?.cancel()   // a pending downshift must not fire into the new source's teardown
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

    /**
     * Arm the post-playback stall watchdog: if we're STILL not playing after a grace window (a mid-stream
     * rebuffer or a player-handoff that won't start — the "plays a while then buffers forever" case),
     * auto-downshift to the smallest healthy source below the current one, KEEPING position. Idempotent —
     * re-arming while already armed is a no-op; it self-cancels on STATE_READY / a fresh source.
     */
    private fun armSustainedRebufferWatchdog() {
        if (!hasReachedPlaying) return   // startup is the failover watchdog's job, not the downshift's
        // NEVER downshift a DIRECT (RD/file-server) stream. The downshift exists for a starved TORRENT
        // swarm; a direct stream isn't bandwidth-limited by peers, so a rebuffer near the end is just RD
        // serving the tail slowly — it recovers on its own. Downshifting here picked the smallest TORRENT
        // and restarted from scratch: the "RD movie ~80% in suddenly reverts to a torrent, every RD does
        // it" bug. A genuinely-dead RD link surfaces as onPlayerError (retried in place), not here.
        if (_currentSource.value?.isDirect == true) return
        if (rebufferWatchdogJob?.isActive == true) return
        rebufferWatchdogJob = viewModelScope.launch {
            delay(SUSTAINED_REBUFFER_TIMEOUT_MS)
            if (_uiState.value !is PlayerUiState.Playing || _rebuffering.value != null) {
                failoverToSmaller()
            }
        }
    }

    /** Downshift to the smallest healthy source below the current one on a sustained underrun, keeping
     *  position (selectSource saves progress first; maybeSeekToResume restores on the new player). No-op
     *  when nothing smaller qualifies — we then just keep waiting on the current source. */
    private fun failoverToSmaller() {
        // A DIRECT source has no swarm to downshift away from, and latestStatus is null for it (so the
        // near-complete guard below can't protect it) — never downshift a direct stream to a torrent.
        if (_currentSource.value?.isDirect == true) return
        // NEVER downshift when the file is already substantially downloaded — the bytes are (mostly) on
        // disk, so finishing THIS swarm is always faster than abandoning it to restart a fresh torrent
        // from scratch. Was 0.9, which still switched away from a 74-89%-downloaded file: the enraging
        // "shows 90% downloaded, buffers, then switches torrent and starts over" report. At 0.5, half a
        // file from a live swarm always beats a cold restart.
        if ((latestStatus?.progress ?: 0f) >= NEAR_COMPLETE_FRACTION) return
        // Nor when the swarm is clearly FAST enough to keep up — a stall while pulling multiple MB/s is
        // piece SCATTER (the swarm has the bytes but out-of-order) or a decode hiccup, NOT starvation, so
        // a smaller source can't help and just throws the download away. The downshift exists only to
        // rescue a swarm that genuinely can't sustain the bitrate. This is the primary guard for the
        // "300 seeders, 5 MB/s, still switched torrents" case.
        // Deliberately the WIRE rate, not the payload rate. Payload is the honest number to PRINT, but
        // using it here would make this guard release sooner — i.e. make failover MORE aggressive — and
        // that is a playback-reliability change that must not ride along with a labelling fix. Revisit
        // only with a measured waste percentage from the DIAG line in front of you.
        if ((latestStatus?.downloadRateBytes ?: 0) >= HEALTHY_RATE_BYTES) return
        val smaller = findSmallerHealthySource() ?: return
        rebufferWatchdogJob?.cancel()
        _rebuffering.value = null
        selectSource(smaller)
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
        // Clamp the user's quality preference to what the PANEL can show: a 4K source on a 1080p screen
        // is a bigger file, a slower start, and often an HEVC Main10 profile the box can't hardware-decode
        // (the dominant "black screen with audio" trigger). Applies regardless of the settings tier.
        val prefTier = minOf(pref.maxTier, deviceProfile.maxDisplayTier)
        currentNetworkMaxTier = prefTier   // cache for the synchronous smaller-source scan
        // File-server-first: a DIRECT http/hls source plays INSTANTLY with no swarm, so always prefer the
        // best direct stream (within the quality cap) over any torrent. Only fall through to the torrent
        // health-pick when there's no direct source.
        StreamPicker.pickDirect(list, prefTier, deviceProfile.isLowPower)?.let { return it }
        val sizePref = settingsRepository.current().streamSize
        return StreamPicker.pick(list, prefTier, sizePref, deviceProfile.isLowPower) ?: list.first()
    }

    /**
     * Resume-aware pick: reuse the exact saved torrent only when its partial bytes exist on THIS device
     * and the release still satisfies this device's current quality/decoder gates. The info-hash is part
     * of cloud-synced watch history, so blindly reusing it made a weaker TV inherit a phone/strong-TV 4K
     * or otherwise unsuitable release despite the TV's 1080p cap — a device/title-specific crash path.
     * With no useful local partial, freshly rank the best source; the saved TIMESTAMP still resumes.
     */
    private suspend fun pickResumeOrPreferred(list: List<StreamSource>, season: Int?, episode: Int?): StreamSource {
        val preference = networkQualityPreference()
        val preferred = pickPreferred(list, preference)
        val savedHash = libraryRepository.getProgress(mediaId, mediaType, season, episode)?.infoHash
        val resume = savedHash?.let { h -> list.firstOrNull { it.infoHash.equals(h, ignoreCase = true) } }
            ?: return preferred
        if (resume.infoHash.equals(preferred.infoHash, ignoreCase = true)) return preferred

        // Direct links have no reusable local bytes and commonly expire; reranking a fresh direct is
        // both faster and safer. For torrents, a cloud-synced hash from another device is not a cache.
        val locallyCached = !resume.isDirect && withContext(Dispatchers.IO) {
            torrentStreamer.cachedTorrents().any { it.equals(resume.infoHash, ignoreCase = true) }
        }
        val compatible = locallyCached && StreamPicker.isResumeCompatible(
            source = resume,
            candidates = list,
            maxTier = minOf(preference.maxTier, deviceProfile.maxDisplayTier),
            lowPower = deviceProfile.isLowPower,
        )
        if (compatible) {
            diagnostics.breadcrumb("resumeSource reused=${resume.diagTag()}")
            return resume
        }

        diagnostics.event(
            "player_resume_source_reselected",
            mapOf(
                "saved" to resume.diagTag(),
                "picked" to preferred.diagTag(),
                "reason" to if (locallyCached) "device_incompatible" else "no_local_cache",
                "lowPower" to deviceProfile.isLowPower.toString(),
                "displayTier" to deviceProfile.maxDisplayTier.toString(),
            ),
        )
        return preferred
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
        // Attached to any subsequent Crashlytics report without exposing a title/hash/token. If a
        // native/device decoder still terminates one model, the last attempted quality + device tier
        // makes the next report actionable instead of an opaque title-specific crash.
        diagnostics.breadcrumb(
            "sourceStart src=${source.diagTag()} lowPower=${deviceProfile.isLowPower} " +
                "displayTier=${deviceProfile.maxDisplayTier} startOver=${startOverSessionPositionMs != null}",
        )
        // Drop any previous source's preview frames; the new one re-samples once it's playing.
        thumbnails.clear()
        // Record this attempt so failover never loops back to a source we've already tried, and clear
        // the in-flight guard now that a new attempt is actually underway.
        triedInfoHashes.add(source.infoHash)
        transitionInFlight = false
        lastEmittedDownloadedBytes = 0L
        // Fresh source -> reset the VLC-fallback latch and RELEASE any prior VLC player (not just stop:
        // a stopped VLC keeps its surface/vout latched and the next VLC source renders audio-only black).
        // Load-bearing on the manual-select / initial-load paths, which reach startSource WITHOUT going
        // through releaseLocalPlayer. The failover path already released it, so this is then a no-op.
        usingVlcForSource = false
        releaseVlcPlayer()
        // Deliberately does NOT touch the next-episode warm. This runs on every source start, including
        // a mid-episode failover or manual source switch at 85-95% — exactly when the warm is in flight.
        // Blanket-cancelling it there dropped a half-warmed torrent's bytes AND burned its one-shot
        // slot for the rest of the episode. The warm is keyed to (season, episode) now, so an episode
        // hop re-arms it naturally and a same-episode source change leaves it alone.
        // Fresh source — re-arm the smaller-stream hint and start its buffering clock.
        suggestedSmallerForSource = false
        _suggestSmaller.value = false
        _rebuffering.value = null
        rebufferWatchdogJob?.cancel()   // fresh source -> drop any pending downshift from the old one
        hasReachedPlaying = false       // new source starts in the STARTUP phase (failover watchdog owns it)
        firstFrameRendered = false      // fresh source hasn't painted yet — re-arm the no-frame watchdog on READY
        firstFrameWatchdogJob?.cancel()
        foreignAudioChecked = false     // fresh source -> re-check its declared audio languages once
        latestStatus = null
        firstReadyAtMs = 0L
        // The old source's audio-track list is meaningless for the new stream (and on the VLC path it
        // would leave a picker that silently no-ops) — clear until the new player reports its tracks.
        clearAudioTracks()
        audioDecisionMade = false       // fresh source -> pick its audio language once, from scratch
        audioDecidedTrackCount = 0
        audioPickedByUser = false
        bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
        _uiState.value = PlayerUiState.Buffering(
            percent = 0,
            // A direct (RD/file-server) source has no swarm — never show a seeder count on its loading
            // screen (it was carrying the underlying torrent's seeders and looking like a torrent).
            seeders = if (source.isDirect) 0 else source.seeders ?: 0,
            payloadRateBytes = 0,
            label = if (source.isDirect) "Loading…" else "Connecting to peers…",
        )

        streamJob?.cancel()
        directValidationJob?.cancel()   // a stale direct probe must not outlive the source it was for

        // DIRECT http/hls source: no torrent engine, no swarm, no readiness gate — hand the URL straight
        // to the player ("file-server-first"). onPlayerError still fails over to the next source (incl. a
        // torrent) if the direct link is dead; STATE_READY -> maybeSeekToResume restores position.
        if (source.isDirect) {
            val directUrl = source.directUrl!!
            // OFFLINE file:// — the bytes are already on disk. No HEAD probe (it's not HTTP), no failover
            // (there's nowhere to fail over to), just hand it to the player.
            if (source.isOffline) {
                // No watchdog: a local file has nowhere to fail over to, and the direct startup watchdog
                // would force a "Playback failed" Error screen if the decoder is slow to start. onPlayerError
                // still handles a genuinely corrupt file (ExoPlayer→VLC handoff).
                ensurePlayer(directUrl)
                startProgressTicker()
                return
            }
            // Pre-play validation: a quick HEAD (fallback ranged GET) catches DEAD RD links (4xx/5xx) and
            // few-second FAKE files (tiny Content-Length) BEFORE committing the player — so a poison link
            // fails over in ~1s instead of after the 12s watchdog, preserving the failover budget for real
            // alternates. Lenient on ambiguous responses so a good link is never wrongly rejected.
            directValidationJob = viewModelScope.launch {
                val valid = validateDirectUrl(directUrl, source.requestHeaders)
                // The probe can take seconds and the buffering overlay offers "Switch source" the whole
                // time — if the user (or a failover) moved on meanwhile, this result is for a source we
                // abandoned: acting on it would rebind the old URL or tear down the new source.
                if (_currentSource.value !== source) return@launch
                if (valid) {
                    ensurePlayer(directUrl)
                    startProgressTicker()
                    armDirectWatchdog()
                } else {
                    triedInfoHashes.add(source.infoHash)
                    if (!failoverToNext()) {
                        _uiState.value = PlayerUiState.Error("Couldn't start any source for this title.")
                    }
                }
            }
            return
        }

        streamJob = viewModelScope.launch {
            // Where playback will actually START (a resume point, or a Start-over/failover position) as a
            // fraction of the file, so the engine buffers there first instead of the file head. Matches
            // what maybeSeekToResume will seek to; 0f for a fresh from-start play.
            val startFraction = resumeStartFraction()
            torrentStreamer.start(source, startFraction).collect { status ->
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
                // (a) Dead/fake swarm: budget elapsed AND the head stopped growing.
                // Once the head is ready, an EOF-moov MP4 is governed by the streamer's separate,
                // bounded tail wait. A complete 32 MB tail piece exposes no partial piece progress, so
                // the head is expected to remain flat while a healthy thin swarm finishes that piece.
                val awaitingTail = latestStatus?.awaitingStartupTail == true
                val deadSwarm = !awaitingTail &&
                    sinceStart >= FAILOVER_BUFFER_BUDGET_MS && stalled >= WATCHDOG_STALL_MS
                // (b) Can't-decode source: the streamer handed us a URL (READY) but the player still hasn't
                //     reached first frame a good while later, WHILE bytes keep flowing. That's the missing-
                //     mp4-moov / scattered-head trap — the head-flat check above never catches it because
                //     the overall download is progressing. Bail to another release instead of grinding
                //     tens-of-% in. head>lastHead (above) tells us the torrent is alive.
                val playerStuck = _player.value?.playbackState == Player.STATE_BUFFERING
                val readyButNotPlaying = firstReadyAtMs > 0L &&
                    now - firstReadyAtMs >= STARTUP_PLAY_TIMEOUT_MS &&
                    playerStuck && head > 0L
                if (deadSwarm || readyButNotPlaying) {
                    if (!failoverToNext()) {
                        _uiState.value = PlayerUiState.Error("Couldn't start any source for this title.")
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * Direct (file-server) links have no swarm and no readiness gate, so the torrent buffering watchdog
     * never arms for them — a dead/403/redirect or a half-alive CDN that connects but never produces a
     * frame would otherwise hang in BUFFERING forever (the "found sources, started ExoPlayer, none
     * played, had to revert to torrent" bug). A direct link must respond fast: if it isn't Playing within
     * a short budget, fail over to the next source (which may be a torrent). A healthy link flips to
     * Playing first, so this self-cancels via the state check.
     */
    /** Turn an opaque ExoPlayer error code into a human message + implied next step, so the user sees a
     *  cause + a fix instead of "Playback error (2014)". */
    private fun friendlyPlaybackError(error: PlaybackException): String = when (error.errorCode) {
        in 2000..2999 -> "Lost the connection to this stream — it stalled or the source went away. Try another source."
        in 3000..3999 -> "This release is in a format the player couldn't read. Try another source."
        in 4000..4999 -> "This device can't decode this video's codec. Try another source."
        else -> (error.localizedMessage?.takeIf { it.isNotBlank() } ?: "Playback failed") + ". Try another source."
    }

    private fun armDirectWatchdog() {
        // An offline file:// source has nowhere to fail over — never let the startup watchdog error it out.
        if (_currentSource.value?.isOffline == true) return
        bufferWatchdogJob?.cancel()
        bufferWatchdogJob = viewModelScope.launch {
            delay(DIRECT_STARTUP_TIMEOUT_MS)
            if (_uiState.value !is PlayerUiState.Playing && !_isCasting.value) {
                if (!failoverToNext()) {
                    _uiState.value = PlayerUiState.Error("Couldn't start any source for this title.")
                }
            }
        }
    }

    /** A source can reach STATE_READY (uiState=Playing) yet never paint a frame — a botched surface
     *  hand-off on a mid-stream failover plays AUDIO over a BLACK screen, and in Playing there's no
     *  overlay, so the user is stranded with no switch-source button. If no frame renders within the
     *  window, force the source panel open — the always-reachable escape hatch. We do NOT auto-failover
     *  (a slow-but-fine stream might just be late to paint; let the user decide). Authoritative on the
     *  [firstFrameRendered] FLAG, not the timer, so a stream that DID paint is never interrupted. */
    private fun armFirstFrameWatchdog() {
        firstFrameWatchdogJob?.cancel()
        // Offline file:// has nowhere to switch to and can decode slowly on a cold codec — never nag it.
        if (_currentSource.value?.isOffline == true) return
        firstFrameWatchdogJob = viewModelScope.launch {
            delay(FIRST_FRAME_TIMEOUT_MS)
            if (firstFrameRendered ||
                _uiState.value !is PlayerUiState.Playing ||
                _isCasting.value ||
                transitionInFlight ||
                _currentSource.value?.isOffline == true
            ) return@launch
            // Audio (and subtitles) play but no frame ever painted: the video decoder can't render this
            // stream — almost always a codec/profile ExoPlayer's hardware path rejects (HEVC 10-bit / HDR
            // / AVC High level the TV won't decode).
            diagnostics.event(
                "player_no_first_frame",
                mapOf(
                    "backend" to if (usingVlcForSource) "vlc" else "exo",
                    "source" to _currentSource.value.diagTag(),
                    "tv" to deviceProfile.isTv.toString(),
                ),
            )
            if (!usingVlcForSource) {
                // libVLC bundles software decoders for everything ExoPlayer's hardware path can't do —
                // hand it the SAME source at the live position. This actually FIXES the black screen
                // (the "every codec plays" fallback) instead of only offering a manual escape.
                val url = currentMediaUrl
                val pos = _currentPlayer.value?.currentPosition?.coerceAtLeast(0L) ?: 0L
                if (url != null) { switchToVlc(url, pos); return@launch }
            }
            // Already on VLC and STILL no frame (or no url) — a genuinely broken stream: open the source
            // picker so the user is never stranded on black (always-reachable, focus-independent).
            _forceSourcePanel.value = true
        }
    }

    /** Short-timeout client used only for the pre-play direct-link HEAD check. */
    private val validationClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Quick pre-play check of a direct/RD link. Returns false ONLY on a DEFINITIVE negative — a 4xx/5xx
     * (dead/removed link) or a tiny Content-Length (a few-second fake sample). Ambiguous responses
     * (timeout, HEAD-not-allowed, no length) return true so a good link is never wrongly rejected — the
     * duration check + startup watchdog still cover those. Sends the source's required headers + UA so
     * the probe isn't itself 403'd.
     */
    private suspend fun validateDirectUrl(url: String, headers: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            val ua = headers["User-Agent"] ?: headers["user-agent"] ?: DEFAULT_USER_AGENT
            fun build(head: Boolean): Request = Request.Builder().url(url).apply {
                if (head) head() else header("Range", "bytes=0-1")
                header("User-Agent", ua)
                headers.forEach { (k, v) ->
                    if (!k.equals("User-Agent", ignoreCase = true) && k.isNotBlank() && v.isNotBlank()) header(k, v)
                }
            }.build()
            val headResp = runCatching { validationClient.newCall(build(head = true)).execute() }.getOrNull()
            val resp = when {
                // Many hosts refuse HEAD (405/501 — or 403 it) while serving GET fine, so a HEAD error
                // status is NOT definitive: re-probe with the ranged GET and judge on that instead.
                headResp != null && headResp.code in 400..599 -> {
                    runCatching { headResp.close() }
                    runCatching { validationClient.newCall(build(head = false)).execute() }.getOrNull()
                        ?: return@withContext true   // couldn't reach it to judge — let the watchdog decide
                }
                headResp != null -> headResp
                else -> runCatching { validationClient.newCall(build(head = false)).execute() }.getOrNull()
                    ?: return@withContext true   // couldn't reach it to judge — let the watchdog decide
            }
            resp.use { r ->
                if (r.code in 400..599) return@withContext false   // dead / removed
                // A ranged GET normally has Content-Length: 2 while Content-Range carries the full
                // object size. HLS manifests are also legitimately only a few KB.
                val contentRange = r.header("Content-Range")
                val len = contentRange?.substringAfterLast('/')?.toLongOrNull()
                    ?: r.header("Content-Length")?.toLongOrNull().takeIf { contentRange == null }
                val pathExt = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                val contentType = r.header("Content-Type").orEmpty()
                val isManifest = pathExt == "m3u8" || contentType.contains("mpegurl", ignoreCase = true)
                // Opaque HLS/proxy endpoints commonly return a tiny manifest as text/plain or
                // application/octet-stream. Apply the fake-size floor only when the URL/MIME actually
                // identifies a progressive media object; ambiguous small responses go to the bounded
                // startup watchdog instead of being falsely discarded.
                val knownProgressive = pathExt in PROGRESSIVE_VIDEO_EXTENSIONS ||
                    (contentType.startsWith("video/", ignoreCase = true) && !isManifest)
                if (knownProgressive && len != null && len in 1 until MIN_DIRECT_FILE_BYTES) {
                    return@withContext false
                }
                true
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
        if (transitionInFlight) return true
        val remaining = _sources.value.filter { it.infoHash !in triedInfoHashes }
        if (remaining.isEmpty()) return false
        // The source we're failing AWAY from: a direct (RD/file-server) link fails INSTANTLY and for
        // free (no swarm), and RD lists many dead/removed/fake [RD+] entries that only fail at play
        // time — so walking past them must NOT be bounded by the small TORRENT failover cap or the user
        // dead-ends mid-list. Count direct failovers separately with a generous budget; expensive
        // torrent failovers stay tightly capped.
        val leavingDirect = _currentSource.value?.isDirect == true
        if (leavingDirect) {
            if (directFailoverCount >= MAX_DIRECT_FAILOVERS) return false
        } else if (failoverCount >= MAX_FAILOVERS) {
            return false
        }
        // Claim the shared transition slot AFTER the budget checks (so a budget-exhausted return never
        // leaves the guard stuck). Synchronous, before any suspension, so two triggers can't both fire.
        if (!beginTransition()) return true
        if (leavingDirect) directFailoverCount++ else failoverCount++
        // currentNetworkMaxTier is already display-clamped by pickPreferred; clamp the fallback read too.
        val pref = currentNetworkMaxTier ?: minOf(networkQualityPreference().maxTier, deviceProfile.maxDisplayTier)
        val sizePref = settingsRepository.current().streamSize
        // Prefer another DIRECT (RD/file-server) link before dropping to a torrent — an expired/revoked
        // RD URL mid-stream should retry the next INSTANT RD source, not silently downshift to a slow
        // swarm (StreamPicker ranks by seeders, which directs have 0 of, so a torrent always won). Mirrors
        // the file-server-first pickPreferred(). Falls through to the torrent health-pick only when no
        // untried direct remains — the correct final fallback.
        val direct = StreamPicker.pickDirect(remaining, pref, deviceProfile.isLowPower)
        val next = if (direct != null) {
            direct
        } else {
            StreamPicker.pick(remaining, pref, sizePref, deviceProfile.isLowPower) ?: remaining.first()
        }
        _suggestSmaller.value = false
        diagnostics.event(
            "player_failover",
            mapOf(
                "from" to _currentSource.value.diagTag(),
                "to" to next.diagTag(),
                "remaining" to remaining.size.toString(),
            ),
        )
        _uiState.value = PlayerUiState.Buffering(
            percent = 0,
            seeders = if (next.isDirect) 0 else next.seeders ?: 0,
            payloadRateBytes = 0,
            label = "Trying another source…",
        )
        // Persist the LIVE position BEFORE tearing the player down — _player.value still holds the old
        // player with the real position, so maybeSeekToResume() restores it on the rebuilt player (keyed
        // by media/season/episode, not infoHash, so it works across a failover to a different release).
        // Without this, a mid-playback failover restarted the new source at 0:00 (the "10 min in, resets
        // to 0:00" bug). saveProgressNow() already no-ops on a 0 position, so it can't clobber a resume.
        saveProgressNow()
        // Keep the just-failed partial in cache, then tear the poisoned player down so ensurePlayer()
        // rebuilds a clean one on the new URL. startSource() re-arms failoverInFlight = false.
        stopActiveStream(removeFiles = false)
        releaseLocalPlayer()
        startSource(next)
        return true
    }

    /** Release the current ExoPlayer so the next [ensurePlayer] builds a clean one (failover + episode
     *  switch both need a FRESH player, never the old one's leftover position/state). */
    private fun releaseLocalPlayer() {
        bufferWatchdogJob?.cancel()
        rebufferWatchdogJob?.cancel()
        firstFrameWatchdogJob?.cancel()
        progressTickJob?.cancel()
        // Defer the ExoPlayer release until AFTER the UI unbinds (mirror of the VLC pattern below).
        // Releasing synchronously here left the TV PlayerView bound to a RELEASED player for one
        // recompose frame — its setPlayer(next/null) then calls clearVideoSurfaceView() on the freed
        // player: the rapid-source-switching crash ("switch 2-3 sources fast and it dies"). Pause now
        // so there's no double audio during the hand-off; release once the UI has rebound.
        val exo = _player.value
        playerListener?.let { exo?.removeListener(it) }
        exo?.let { runCatching { it.playWhenReady = false } }
        _player.value = null
        exo?.let { e ->
            viewModelScope.launch { try { delay(80) } finally { runCatching { e.release() } } }
        }
        // RELEASE (not just stop) the VLC player, and do it AFTER the UI unbinds. The old code only
        // stop()'d it, so its vout/surface state stayed latched: on a later source that remounted the
        // TV PlayerView with a fresh SurfaceView, VLC's stale "already attached" latch skipped the
        // re-attach and the new surface got NO video — audio-only BLACK (the reported "double player").
        // Releasing forces the next VLC use to build a virgin player that attaches cleanly. Defer the
        // release ~80ms (mirrors the Exo hand-off in ensureVlcPlayer) so PlayerView detaches its surface
        // from a LIVE player first — releasing synchronously would clearVideoSurfaceView on a freed
        // native player and crash.
        val outgoingVlc = vlcPlayer
        vlcPlayer = null
        if (!_isCasting.value) _currentPlayer.value = null
        outgoingVlc?.let { v ->
            viewModelScope.launch { try { delay(80) } finally { runCatching { v.release() } } }
        }
        currentMediaUrl = null
        sourceErrorRetries = 0
    }

    private fun handleStatus(status: StreamStatus, source: StreamSource) {
        // Feed the buffering watchdog's head-progress signal on every emission.
        lastEmittedDownloadedBytes = status.contiguousHeadBytes
        // Mark when the source first became READY (a URL exists). The startup watchdog uses this to tell
        // "player can't reach first frame" (e.g. missing mp4 moov) apart from "still filling the head".
        if (status.streamUrl != null && firstReadyAtMs == 0L) {
            firstReadyAtMs = android.os.SystemClock.elapsedRealtime()
        }
        // Keep the latest status around even once we're Playing (the early-returns below skip the rest
        // of this function then) so a mid-playback stall has a live rate to estimate the wait from. If
        // a rebuffer is currently showing, refresh its ETA on every emission so the badge counts down.
        latestStatus = status
        if (_rebuffering.value != null) {
            _rebuffering.value = RebufferState(
                etaSeconds = etaToResume(status),
                payloadRateBytes = status.payloadRateBytes,
            )
        }
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
                // Progress toward PLAYABLE (not toward the whole multi-GB file) so the bar fills to
                // ~100% exactly as we become ready — instead of sitting near 0% during "Almost ready…".
                percent = bufferFillPercent(status),
                seeders = displaySeeders(status, source),
                payloadRateBytes = status.payloadRateBytes,
                label = "Almost ready…",
                // Prefer the streamer's gate-accurate ETA (head + mp4 moov tail); fall back to the
                // local head-only estimate only when the streamer can't estimate yet.
                etaSeconds = status.etaSeconds ?: etaToPlaying(status),
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

        // Name the actual blocker. A generic "Buffering…" next to a chunk bar with a solid teal head is
        // what made the wait feel broken — the user could SEE downloaded data and was told nothing about
        // why it wasn't enough. The gate knows exactly which requirement is outstanding, so say it.
        val label = when {
            status.state == StreamState.METADATA -> "Fetching torrent metadata…"
            status.state == StreamState.IDLE -> "Connecting to peers…"
            status.startupBlocker == StartupBlocker.MOOV_INDEX ->
                "Head buffered — fetching this MP4's index…"
            status.startupBlocker == StartupBlocker.PLAYHEAD -> "Buffering at your resume point…"
            else -> "Buffering…"
        }
        _uiState.value = PlayerUiState.Buffering(
            percent = bufferFillPercent(status),
            seeders = displaySeeders(status, source),
            payloadRateBytes = status.payloadRateBytes,
            label = label,
            etaSeconds = status.etaSeconds ?: etaToPlaying(status),
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
        val needed = (PLAYABLE_TARGET_BYTES - status.contiguousHeadBytes).coerceAtLeast(0L)
        // Payload rate: `needed` is payload bytes, so dividing by the wire rate (overhead + discarded
        // duplicates) shortened every countdown by the waste fraction.
        val rate = status.payloadRateBytes.takeIf { it > 0 } ?: status.downloadRateBytes
        val downloadSecs = when {
            needed == 0L -> 0
            rate > 0 -> (needed / rate).toInt()
            else -> return null
        }
        return (downloadSecs + PREPARE_MARGIN_SECONDS).takeIf { it in 1..900 }
    }

    /**
     * Estimated seconds until a mid-playback stall can resume = time to download a small forward
     * cushion ([REBUFFER_CUSHION_SECONDS] of video) at the current rate. The cushion's byte size is
     * derived from the file's average bitrate (fileLength ÷ duration). When the rate is at/above the
     * bitrate this lands near the cushion length (a quick blip); when it's below, the estimate grows,
     * honestly signalling "slow connection — this'll be a bit". Null when the rate/size is unknown.
     */
    private fun etaToResume(status: StreamStatus): Int? {
        // Payload rate — the cushion is measured in file bytes, not wire bytes.
        val rate = status.payloadRateBytes.takeIf { it > 0 } ?: status.downloadRateBytes
        if (rate <= 0) return null
        val durMs = _currentPlayer.value?.duration?.takeIf { it > 0 } ?: return null
        val infoHash = _currentSource.value?.infoHash ?: return null
        val fileLen = torrentStreamer.fileLength(infoHash).takeIf { it > 0 } ?: return null
        val bytesPerSec = fileLen.toDouble() / (durMs / 1000.0)
        val neededBytes = bytesPerSec * REBUFFER_CUSHION_SECONDS
        return (neededBytes / rate).toInt().plus(1).takeIf { it in 1..900 }
    }

    /**
     * The single progress bar shown while starting up represents "how close are we to PLAYABLE",
     * not "how much of the whole file is downloaded". Streaming only needs a small head to start, so
     * whole-file progress would crawl near 0% the entire time and feel broken.
     *
     * The denominator is the readiness gate's OWN requirement (header + playhead + the container's real
     * index need), so the bar reaches 100% exactly when the gate opens. The old head-only denominator
     * pinned the bar at 100% the moment the first piece landed and then left it sitting there for the
     * whole moov fetch — a bar claiming "done" over an engine that was still waiting. Falls back to the
     * head-only figure only before the gate has published a requirement (metadata phase).
     */
    private fun bufferFillPercent(status: StreamStatus): Int {
        val required = status.startupRequiredBytes
        if (required <= 0L) {
            return ((status.contiguousHeadBytes.toFloat() / PLAYABLE_TARGET_BYTES) * 100f)
                .toInt().coerceIn(0, 100)
        }
        // Straight from the gate's own StartDecision.fillFraction — no second implementation. The copy
        // that used to live here re-derived (required - remaining) / required and lost that function's
        // "never 1.0 while a requirement is outstanding" clamp, so it printed "100% · Almost ready…"
        // over a shut gate: the EOF requirement is a notional 8 MB while the credit is counted in real
        // finished blocks of 32 MB pieces, so one part-finished piece pays the whole band off.
        return (status.startupFillFraction * 100f).toInt().coerceIn(0, 100)
    }

    /**
     * Seeder count to SHOW. libtorrent's numSeeds() is CONNECTED seeds; a source's `seeders` is the
     * addon's ADVERTISED swarm size. Substituting the second for the first whenever the first is 0 put
     * "5,000 seeders · 0 KB/s" on screen for a swarm we were connected to nobody in. Keep the advertised
     * number only for the phase where nothing else can possibly be known (no torrent running yet), and
     * report the honest live count — including a live 0 — from then on.
     */
    private fun displaySeeders(status: StreamStatus, source: StreamSource): Int =
        if (status.state == StreamState.IDLE || status.state == StreamState.METADATA) {
            status.seeders.takeIf { it > 0 } ?: (source.seeders ?: 0)
        } else {
            status.seeders
        }

    /**
     * Gentle nudge: if the head-buffer has dragged on past [BUFFERING_NAG_MS] with a low download
     * rate AND a smaller, well-seeded source exists below the current one, raise the one-shot
     * [suggestSmaller] hint. Fires at most once per source ([suggestedSmallerForSource]).
     */
    private fun maybeSuggestSmaller(downloadRateBytes: Int) {
        if (_currentSource.value?.isDirect == true) return   // "try a smaller stream" is a torrent concept
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

    /**
     * The ExoPlayer media-source factory. For a DIRECT source it carries the host's required request
     * headers (Referer/Origin/User-Agent from the addon's behaviorHints.proxyHeaders) plus a real
     * browser User-Agent — without these the default data source sends no UA and header-gated CDNs 403,
     * which is the root cause of "found a direct stream, ExoPlayer started, nothing played". Torrent
     * sources pass an empty header map (no-op) but still get a real UA on the local HTTP-server requests.
     * Wrapped in [DefaultDataSource.Factory] so non-http schemes still resolve, mirroring what the bare
     * DefaultMediaSourceFactory(context) does internally.
     */
    @OptIn(UnstableApi::class)
    private fun buildMediaSourceFactory(): DefaultMediaSourceFactory {
        val src = _currentSource.value
        val headers = src?.takeIf { it.isDirect }?.requestHeaders ?: emptyMap()
        val userAgent = headers["User-Agent"] ?: headers["user-agent"] ?: DEFAULT_USER_AGENT
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(appContext, http)
        return DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(SOURCE_LOAD_RETRIES))
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
        // Steady-state READY re-emissions carry the same URL and must be no-ops.
        if (existing != null && currentMediaUrl == url) return
        if (existing != null && _currentSource.value?.isDirect != true) {
            // TORRENT source switch / episode hop: REUSE the same ExoPlayer + PlayerView surface so the
            // video doesn't black out on the hand-off (releasing+rebuilding tore the surface down ->
            // audio-only black screen on the next episode). Full reset (stop()+clearMediaItems()) wipes
            // any leftover ENDED/error state that otherwise wedged the new media in a permanent buffer.
            existing.stop()
            existing.clearMediaItems()
            // Drop any audio override from the PREVIOUS media: it is keyed to that file's TrackGroup,
            // and trackSelectionParameters survive a media swap. On an episode hop it either did
            // nothing (different group) or, worse, silently re-applied to a coincidentally-identical
            // group — an override the user set on episode 1 quietly steering episode 2. The language
            // preference below is the thing that should carry over, not a per-file override.
            existing.trackSelectionParameters = existing.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setPreferredAudioLanguage(preferredAudioCode)
                .build()
            existing.setMediaItem(buildMediaItem(url))
            existing.prepare()
            // While casting, keep the local surface paused and push the new source to the TV.
            existing.playWhenReady = !_isCasting.value
            sourceErrorRetries = 0
            currentMediaUrl = url
            switchingEpisode = false   // new media bound -> resume progress saves (for the new episode)
            startProgressTicker()      // restart the ticker we cancelled for the hop
            if (_isCasting.value) loadCastMedia(fromPositionMs = 0L)
            return
        }
        // DIRECT source reached with a live player (a switch INTO a direct URL): its required headers +
        // MIME are baked into the MediaSource.Factory at BUILD time — ExoPlayer has no
        // setMediaSourceFactory() — so reusing the old player would replay the previous (header-less)
        // factory and 403. Tear it down and fall through to a fresh build that bakes in THIS source's
        // headers. Rare (direct→direct mid-play switch); a brief rebuild flicker is acceptable.
        if (existing != null) {
            playerListener?.let { existing.removeListener(it) }
            existing.release()
            _player.value = null
            playerListener = null
        }

        // Start playing with a small buffer so we don't sit idle while the torrent fills ahead, but
        // hold a DEEP enough steady-state buffer to ride out a high-bitrate VBR peak without stalling.
        // The old 8 MB byte cap was only ~11 s of a ~6 Mbps 4K stream — a single dense scene drained it
        // and rebuffered mid-stream even at 1.7 MB/s. 24 MB (~33 s of that 4K) absorbs the peaks and is
        // still far under the 60-95 MB that caused OOM/page-eviction on a 1.5 GB box. bufferForPlaybackMs
        // stays 1_500 so first-frame latency is unchanged.
        val lowPower = deviceProfile.isLowPower
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (lowPower) 15_000 else 15_000,  // minBufferMs — rebuild this much before resuming
                if (lowPower) 45_000 else 50_000,  // maxBufferMs
                1_500,                             // bufferForPlaybackMs — begin playback this fast
                4_000,                             // bufferForPlaybackAfterRebufferMs — bigger cushion on resume
            )
            .apply {
                if (lowPower) {
                    // 40 MB (~20-40 s at 1080p) — deep enough to ride out a scatter gap just ahead of the
                    // playhead while the ordered read-ahead window refills, without approaching the
                    // 60-95 MB that OOM'd a 1.5 GB box. Was 24 MB (~6-12 s), which a single missing piece
                    // past the buffer frontier drained before the swarm filled it — the mid-stream rebuffer.
                    // Kept < LOW_POWER_READAHEAD_BYTES (48 MB) so the read never outruns the deadlined window.
                    setTargetBufferBytes(40 * 1024 * 1024)
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
        // For a DIRECT source, the factory also carries the host's required headers + a real browser
        // User-Agent (default DefaultHttpDataSource sends NO UA -> many CDNs 403, the "starts but never
        // plays" bug). Torrents pass an empty header map (no-op) but still get a real UA on the local
        // server, which is harmless.
        val mediaSourceFactory = buildMediaSourceFactory()
        val exo = ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                // Dual/multi-audio releases: prefer the user's language (English by default) and let
                // ExoPlayer auto-detect it. Set before prepare so the FIRST track selection honours it
                // — no audible mid-playback switch when we get it right.
                //
                // This is necessary but NOT sufficient, which is why onTracksChanged re-checks the
                // outcome: DefaultTrackSelector's comparator ranks isWithinRendererCapabilities ABOVE
                // preferredLanguageScore, so an E-AC3/DTS English track on a device with no such
                // decoder loses to an AAC foreign dub, and an UNTAGGED multi-audio file ties on
                // language and falls through to the CHANNEL COUNT tie-break — which on a MULTI
                // release favours the 5.1 dub over the 2.0 original.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(preferredAudioCode)
                    .build()
                setMediaItem(buildMediaItem(url))
                prepare()
                playWhenReady = true
            }
        sourceErrorRetries = 0
        currentMediaUrl = url
        switchingEpisode = false   // new media bound (fresh player) -> resume progress saves

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        // FAKE/SAMPLE RD link: a "[RD+]" result whose real file is only a few seconds (spam
                        // sample) loads fine but reports an implausibly short duration. A real movie/episode
                        // is always minutes long, so treat a sub-2-min direct source as dead and fail over
                        // instead of playing 8 seconds of junk. (Direct only; torrents/legit shorts untouched.)
                        val dur = exo.duration
                        val shortFake = _currentSource.value?.isDirect == true && !usingVlcForSource &&
                            !_isCasting.value && dur != C.TIME_UNSET && dur in 1 until MIN_DIRECT_DURATION_MS
                        if (shortFake) {
                            triedInfoHashes.add(_currentSource.value?.infoHash ?: "")
                            viewModelScope.launch {
                                if (!failoverToNext()) {
                                    _uiState.value = PlayerUiState.Error("Couldn't start any source for this title.")
                                }
                            }
                        } else {
                            sourceErrorRetries = 0 // recovered / playing — forget transient stalls
                            maybeSeekToResume(exo)
                            // Playing — clear the buffering clock, any pending smaller-stream hint, and any
                            // mid-playback rebuffer badge + downshift watchdog (we just recovered).
                            bufferingSinceMs = 0L
                            _suggestSmaller.value = false
                            _rebuffering.value = null
                            rebufferWatchdogJob?.cancel()
                            hasReachedPlaying = true
                            _uiState.value = PlayerUiState.Playing
                            if (!firstFrameRendered) armFirstFrameWatchdog()
                            maybeStartThumbnails(exo)
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        if (_uiState.value !is PlayerUiState.Playing) {
                            val s = _currentSource.value
                            // (Re)start the buffering clock if it was cleared by a prior READY.
                            if (bufferingSinceMs == 0L) {
                                bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
                            }
                            // Carry forward the head-download overlay (same label, same ETA, same
                            // filled bar) instead of resetting to a bare "Buffering…" — the torrent
                            // flow keeps the ETA live, this just stops the player's own buffer phase
                            // from blanking it. That blanking was the visible "done, now buffering
                            // again" second step the user saw.
                            val prev = _uiState.value as? PlayerUiState.Buffering
                            _uiState.value = PlayerUiState.Buffering(
                                percent = prev?.percent ?: 100,
                                seeders = prev?.seeders ?: (s?.seeders ?: 0),
                                payloadRateBytes = prev?.payloadRateBytes ?: 0,
                                label = "Almost ready…",
                                etaSeconds = prev?.etaSeconds,
                            )
                            maybeSuggestSmaller(downloadRateBytes = 0)
                        } else if (_rebuffering.value == null) {
                            // STALL PROBE — the decisive data point for "all chunks downloaded, why does it
                            // still buffer?". Captures, AT the instant of the stall: how much video ExoPlayer
                            // actually had buffered, the player's own byte position, whether the piece under
                            // that position is on disk, and the swarm state. If bufAhead≈0 while
                            // byteAtHead=true, the bytes were RIGHT THERE and the fault is the read/serve
                            // path or the decoder — NOT the swarm. Readable in the on-screen Diagnostics.
                            runCatching {
                                val posMs = exo.currentPosition.coerceAtLeast(0L)
                                val bufAheadMs = (exo.bufferedPosition - posMs).coerceAtLeast(0L)
                                val durMs = exo.duration.takeIf { it > 0L } ?: 0L
                                val hash = activeInfoHash
                                val total = hash?.let { torrentStreamer.fileLength(it) } ?: 0L
                                // Byte offset the player is reading (duration-proportional estimate).
                                val byteAtHead = if (hash != null && durMs > 0L && total > 0L) {
                                    val approxByte = (total.toDouble() * (posMs.toDouble() / durMs)).toLong()
                                    torrentStreamer.isByteAvailable(hash, approxByte)
                                } else null
                                diagnostics.breadcrumb(
                                    "STALL pos=${posMs / 1000}s bufAhead=${bufAheadMs}ms " +
                                        "pieceOnDisk=$byteAtHead prog=${latestStatus?.progress} " +
                                        "rate=${latestStatus?.downloadRateBytes} " +
                                        "seeds=${latestStatus?.seeders} vlc=$usingVlcForSource",
                                )
                            }
                            // Mid-playback stall: keep the frozen frame + Playing state, but raise the
                            // small "buffering ~Xs" badge so it never looks permanently frozen. ETA is
                            // refreshed live from the torrent feed in handleStatus.
                            _rebuffering.value = RebufferState(
                                etaSeconds = latestStatus?.let { etaToResume(it) },
                                payloadRateBytes = latestStatus?.payloadRateBytes ?: 0,
                            )
                            armSustainedRebufferWatchdog()  // sustained underrun -> downshift (keeps position)
                        }
                    }
                    Player.STATE_ENDED -> {
                        // End-of-file is stronger than the player's occasionally-imprecise 99.x%
                        // position. Persist a completed -> started transition before auto-advancing so
                        // another device can never rank the outgoing episode above the next one.
                        if (mediaType == MediaType.TV && _hasNext.value) {
                            autoAdvanceNextEpisode()
                        } else {
                            saveProgressNow()
                        }
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
                    // NOTE: dimensions known != a frame PAINTED. A profile the TV decoder rejects
                    // (HEVC Main10 / HDR / unusual level) reports size here but never renders — audio +
                    // subtitles play over a black screen. onRenderedFirstFrame below is the authoritative
                    // "a frame actually reached the surface" signal that stands the watchdog down.
                    val par = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                    _videoAspect.value = (w * par) / h
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true   // a frame truly painted -> not a black screen
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Enumerate audio tracks for the manual picker (label + language + which is selected).
                latestTracks = tracks
                val audio = exoAudioTracks(tracks)
                publishAudioTracks(audio)
                // No-sound bug (~20% of torrents): AC3 / E-AC3 / DTS / DTS-HD / TrueHD audio that
                // ExoPlayer can't decode on this device (esp. phones, which lack those licensed decoders)
                // -> the video plays but it's SILENT. If the release HAS audio track(s) but the device
                // supports NONE of them, hand the whole stream to libVLC (bundles FFmpeg, decodes them
                // all) at the live position — the audio twin of the "valid torrent, black screen" fix.
                // Guarded so it never fires on the VLC/cast path; once we switch, ExoPlayer is released so
                // it can't loop. A release with a SUPPORTED track (e.g. an AAC alongside the AC3) keeps
                // playing on ExoPlayer (isTypeSupported stays true) and is untouched.
                if (usingVlcForSource || _isCasting.value) return
                // FOREIGN-AUDIO auto-advance: name-based language detection can't catch a foreign film
                // behind a clean Latin-script release name ("Black Box" = the French "Boîte noire" —
                // most of its releases are French but look English). The stream's own track metadata is
                // authoritative: when every audio track DECLARES a language and NONE is English, this
                // source is confidently foreign — advance to the next candidate like a dead link.
                // "und"/missing languages are benign (most legit English releases declare nothing), so
                // they never trigger this. Once per source; only when untried alternatives remain.
                if (!foreignAudioChecked) {
                    val langs = audio.map { it.language }
                    if (langs.isNotEmpty()) {
                        foreignAudioChecked = true
                        // Normalised, so "zho"/"chi"/"zh-Hans" are one language and "eng"/"en-GB"
                        // both count as English. The old raw startsWith("en") also matched nothing
                        // else by luck, but would have read "enm"/"ene" as English.
                        val want = AudioTrackChoice.normalizeCode(preferredAudioCode)
                        val declared = langs.mapNotNull { AudioTrackChoice.normalizeCode(it) }
                        val confidentlyForeign =
                            declared.size == langs.size && declared.none { it == want }
                        if (confidentlyForeign &&
                            _sources.value.any { it.infoHash !in triedInfoHashes }
                        ) {
                            diagnostics.event(
                                "player_foreign_audio_advance",
                                mapOf("langs" to declared.distinct().joinToString(","), "source" to _currentSource.value.diagTag()),
                            )
                            viewModelScope.launch { failoverToNext() }
                            return
                        }
                    }
                }
                // ENGLISH (or whatever the user set) ENFORCEMENT — see AudioTrackChoice for the two
                // ExoPlayer behaviours that make setPreferredAudioLanguage insufficient on its own.
                // Runs once per source, and not at all once the user has picked by hand.
                if (!audioDecisionMade && audio.size > 1) {
                    audioDecisionMade = true
                    val src = _currentSource.value
                    val pick = AudioTrackChoice.choose(
                        tracks = audio,
                        preferredLanguage = preferredAudioCode,
                        releaseLanguageLikely = src?.englishLikely != false,
                        releaseMultiAudio = src?.multiAudio == true,
                    )
                    diagnostics.event(
                        "player_audio_pick",
                        mapOf(
                            "backend" to "exo",
                            "want" to preferredAudioCode,
                            "reason" to pick.reason.name,
                            "confident" to pick.confident.toString(),
                            "tracks" to audio.joinToString("|") {
                                "${it.language ?: "-"}/${if (it.supported) "ok" else "nodec"}"
                            },
                            "source" to src.diagTag(),
                        ),
                    )
                    if (pick.needsFallbackDecoder) {
                        // The user's language IS in this file — this device just can't decode it
                        // (E-AC3/DTS-HD/TrueHD on a phone). ExoPlayer would silently settle for the
                        // foreign dub because renderer capability outranks language in its comparator,
                        // and the existing isTypeSupported rescue can't see it (some OTHER track is
                        // decodable). libVLC bundles FFmpeg and plays it.
                        val url = currentMediaUrl
                        if (url != null) {
                            diagnostics.event("player_english_unsupported_vlc", mapOf("source" to src.diagTag()))
                            switchToVlc(url, exo.currentPosition.coerceAtLeast(0L))
                            return
                        }
                    }
                    val target = pick.trackId
                    if (target != null && audio.firstOrNull { it.id == target }?.selected != true) {
                        applyExoAudioOverride(exo, target)
                    }
                }
                val hasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                if (hasAudio && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) {
                    val url = currentMediaUrl ?: return
                    val pos = exo.currentPosition.coerceAtLeast(0L)   // read from THIS Exo, still alive here
                    switchToVlc(url, pos)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val p = _player.value
                diagnostics.breadcrumb("exoError code=${error.errorCode} src=${_currentSource.value.diagTag()} played=$hasReachedPlaying")
                if (_currentSource.value?.isDirect == true && !_isCasting.value) {
                    val isIoErr = error.errorCode in 2000..2999
                    // MID-PLAYBACK on a direct link (it already reached Playing): a transient IO blip — an
                    // RD/CDN hiccup, a 5xx, a momentary network drop — recovers by re-preparing the SAME
                    // url at the same position. DON'T nuke a working stream 80% in and revert to torrent
                    // (the "played fine then suddenly switched to a torrent, and every stream reverted"
                    // bug: one network wobble tripped every direct source's startup-style failover). Retry
                    // a few times first; only fall over once it's genuinely dead.
                    if (hasReachedPlaying && isIoErr && p != null && sourceErrorRetries < MAX_SOURCE_RETRIES) {
                        sourceErrorRetries++
                        _uiState.value = PlayerUiState.Buffering(
                            percent = 0,
                            seeders = 0,
                            payloadRateBytes = 0,
                            label = "Reconnecting…",
                        )
                        viewModelScope.launch {
                            delay(SOURCE_RETRY_BACKOFF_MS * sourceErrorRetries)
                            if (_player.value === p) runCatching { p.prepare(); p.playWhenReady = true }
                        }
                        return
                    }
                    // STARTUP failure (a dead link that never played) OR retries exhausted: a 403/404/
                    // redirect/dead host won't recover by re-preparing, and isn't a codec VLC can fix. Drop
                    // STRAIGHT to the next source so a dead direct URL never strands the user on a spinner.
                    viewModelScope.launch {
                        if (!failoverToNext()) {
                            _uiState.value = PlayerUiState.Error(friendlyPlaybackError(error))
                        }
                    }
                    return
                }
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
                        payloadRateBytes = 0,
                        label = "Reconnecting…",
                    )
                    viewModelScope.launch {
                        delay(SOURCE_RETRY_BACKOFF_MS * sourceErrorRetries)
                        // Skip if a backend switch released/replaced this exact player during the backoff
                        // (identity check) — re-preparing a torn-down player is a crash path.
                        if (_player.value === p) runCatching { p.prepare(); p.playWhenReady = true }
                    }
                } else if (isParse && !usingVlcForSource && !_isCasting.value && currentMediaUrl != null) {
                    // ExoPlayer can't parse/decode this container/codec even after retries (e.g. an
                    // XviD/AVI the codec heuristic didn't catch). Hand the SAME source to the libVLC
                    // fallback (bundles FFmpeg, plays everything) at the current position before giving
                    // up on it — this is the "every codec works" path.
                    val pos = exo.currentPosition.coerceAtLeast(0L)   // resume pos from the Exo being torn down
                    switchToVlc(currentMediaUrl!!, pos)
                } else {
                    // Per-source retries are spent and VLC can't help (or already tried). Before
                    // dead-ending, hand off to the next untried source automatically; only surface the
                    // error (with its code) once every candidate is exhausted. Casting can't fail over.
                    val msg = friendlyPlaybackError(error)
                    if (!_isCasting.value) {
                        viewModelScope.launch {
                            if (!failoverToNext()) {
                                _uiState.value = PlayerUiState.Error(msg)
                            }
                        }
                    } else {
                        _uiState.value = PlayerUiState.Error(msg)
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

        // Capture the OUTGOING ExoPlayer but DON'T release it yet. Pause it now (no double-audio while
        // VLC spins up), then release it only AFTER the UI has rebound to VLC — otherwise PlayerView
        // rebinds and calls clearVideoSurfaceView() on a RELEASED player (a crash). Single transition
        // guard already prevents a second path releasing it too.
        val outgoingExo = _player.value
        val outgoingListener = playerListener
        outgoingExo?.let { exo ->
            outgoingListener?.let { runCatching { exo.removeListener(it) } }
            runCatching { exo.playWhenReady = false }
        }
        _player.value = null
        playerListener = null

        val vlc = existing ?: VlcPlayer(vlcEngine, appContext).also { built ->
            vlcPlayer = built
            built.addListener(buildVlcListener(built))
        }
        // Authoritative VLC "frames are rendering" signal (libVLC's Vout event) — the Media3
        // onVideoSizeChanged proxy can miss on a late vout, and the watchdog then popped the source
        // panel over a video that was playing FINE. Re-set on every (re)use: it's the current source's
        // watchdog this must stand down.
        vlc.onFirstFrame = { firstFrameRendered = true }
        // Audio-track plumbing for the VLC backend. libVLC only knows its elementary streams once the
        // demuxer has opened the file, so we can't read them here — ESAdded/ESSelected call back and we
        // enumerate then. Without this the picker list stayed EMPTY on every VLC source and the audio
        // button was never composed (both surfaces gate on the list), which is the "no audio-track
        // selector available" half of the Mutiny report.
        vlc.onAudioTracksChanged = { refreshVlcAudioTracks(vlc) }
        // Reusing a VlcPlayer across a switch: drop its STALE surface binding so PlayerView's rebind
        // triggers a real re-attach. Without this, attachSurface() early-returns on the same SurfaceView
        // and the vout is never reattached -> the exact "audio plays but NO video" bug.
        if (existing != null) existing.detachSurface()
        // A direct source falling back to libVLC (rare — a direct stream in a codec ExoPlayer can't
        // decode) must carry its host headers too, or libVLC 403s the same way. No-op for torrents.
        vlc.setRequestHeaders(_currentSource.value?.takeIf { it.isDirect }?.requestHeaders ?: emptyMap())
        // Preferred SPOKEN language, as a media option so it applies at the FIRST track selection —
        // libVLC with no --audio-language takes the container's default/first audio ES, which on a
        // Chinese-sourced MULTI WEB-DL is Mandarin. Must be set before setMediaItem builds the Media.
        vlc.setPreferredAudioLanguages(AudioTrackChoice.vlcPreferenceList(preferredAudioCode))
        vlc.setMediaItem(buildMediaItem(url), startPositionMs)
        vlc.prepare()
        vlc.playWhenReady = !_isCasting.value
        currentMediaUrl = url
        switchingEpisode = false   // new media bound (VLC) -> resume progress saves
        if (!_isCasting.value) _currentPlayer.value = vlc
        startProgressTicker()
        // Release the outgoing ExoPlayer AFTER the UI has had a frame to rebind to VLC, so PlayerView
        // detaches its surface from the LIVE old player (already paused above), not a released one.
        outgoingExo?.let { exo ->
            viewModelScope.launch {
                // finally, not a plain sequence: if the VM clears during the delay, the cancellation
                // must still release this player or its hardware decoder leaks (it's no longer in
                // _player.value, so onCleared can't reach it).
                try {
                    delay(80)
                } finally {
                    runCatching { exo.release() }
                }
            }
        }
    }

    /** Lean transport listener for the VLC backend (READY -> Playing + resume, ENDED -> next). */
    private fun buildVlcListener(vlc: VlcPlayer): Player.Listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    maybeSeekToResume(vlc)
                    bufferingSinceMs = 0L
                    _suggestSmaller.value = false
                    _rebuffering.value = null
                    rebufferWatchdogJob?.cancel()
                    hasReachedPlaying = true
                    _uiState.value = PlayerUiState.Playing
                    if (!firstFrameRendered) armFirstFrameWatchdog()
                }
                Player.STATE_BUFFERING -> {
                    if (_uiState.value !is PlayerUiState.Playing) {
                        // Carry the head-download overlay through VLC's own buffer (see the ExoPlayer
                        // path above) so the unified "Almost ready…" + ETA + filled bar stays put.
                        val prev = _uiState.value as? PlayerUiState.Buffering
                        _uiState.value = PlayerUiState.Buffering(
                            percent = prev?.percent ?: 100,
                            seeders = prev?.seeders ?: (_currentSource.value?.seeders ?: 0),
                            payloadRateBytes = prev?.payloadRateBytes ?: 0,
                            label = "Almost ready…",
                            etaSeconds = prev?.etaSeconds,
                        )
                        // A VLC handoff that can't reach first frame (e.g. fell back for a SLOW source,
                        // which VLC can't fix) should also downshift rather than buffer forever.
                        armSustainedRebufferWatchdog()
                    } else if (_rebuffering.value == null) {
                        // Mid-playback stall on the VLC backend — same small badge as the ExoPlayer path.
                        _rebuffering.value = RebufferState(
                            etaSeconds = latestStatus?.let { etaToResume(it) },
                            payloadRateBytes = latestStatus?.payloadRateBytes ?: 0,
                        )
                        armSustainedRebufferWatchdog()
                    }
                }
                Player.STATE_ENDED -> {
                    if (mediaType == MediaType.TV && _hasNext.value) {
                        autoAdvanceNextEpisode()
                    } else {
                        saveProgressNow()
                    }
                }
                else -> Unit
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            val w = videoSize.width
            val h = videoSize.height
            if (w > 0 && h > 0) {
                firstFrameRendered = true   // VLC decoded a real frame -> the no-frame watchdog stands down
                val par = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                _videoAspect.value = (w * par) / h
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgressNow()
        }

        override fun onPlayerError(error: PlaybackException) {
            // VLC is the last-resort backend — nothing left to re-prepare or switch to for THIS
            // source, so a VLC error goes straight to the next candidate (or the terminal error).
            val msg = friendlyPlaybackError(error)
            if (!_isCasting.value) {
                viewModelScope.launch {
                    if (!failoverToNext()) _uiState.value = PlayerUiState.Error(msg)
                }
            } else {
                _uiState.value = PlayerUiState.Error(msg)
            }
        }
    }

    /** Hand the CURRENT source to VLC at [startPositionMs] (ExoPlayer couldn't decode it). Self-guards
     *  the single transition slot so it can't race a failover/select; the swap is synchronous, so it
     *  releases the slot on return. */
    private fun switchToVlc(url: String, startPositionMs: Long) {
        if (!beginTransition()) return   // a failover/select transition already owns the slot — let it win
        diagnostics.event("player_vlc_fallback", mapOf("source" to _currentSource.value.diagTag()))
        _uiState.value = PlayerUiState.Buffering(
            percent = 0,
            seeders = if (_currentSource.value?.isDirect == true) 0 else _currentSource.value?.seeders ?: 0,
            payloadRateBytes = 0,
            label = "Switching player…",
        )
        currentMediaUrl = null   // force ensureVlcPlayer to (re)load even if the url matches
        // The dying ExoPlayer's track list describes a stream that is about to stop existing. Leaving
        // it up left the audio button on screen carrying dead ids — and since selectAudioTrack read
        // _player (now null), every tap was a silent no-op against a picker that still rendered. VLC
        // republishes its own list from ESAdded within a second or two.
        clearAudioTracks()
        audioDecisionMade = false   // the new backend gets its own decision
        audioDecidedTrackCount = 0
        audioPickedByUser = false   // the ids the user picked from belonged to the dying player
        // try/finally: a throw from native libVLC construction/prepare must NOT leak the transition slot
        // true forever — that permanently no-ops selectSource() and failoverToNext(), which is the
        // "switch-source button does nothing from every state" wedge.
        try {
            ensureVlcPlayer(url, startPositionMs)
        } finally {
            transitionInFlight = false   // the Exo->VLC swap completed synchronously; release the slot
        }
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

    /**
     * Force a specific AUDIO track — the manual fix when the automatic pick lands wrong.
     *
     * BACKEND-AWARE. It used to open with `val exo = _player.value ?: return`, which meant that after a
     * mid-stream Exo->VLC failover (ensureVlcPlayer nulls `_player`) every tap on the picker was a
     * silent no-op: the panel closed, the row didn't even get its checkmark, and the audio never
     * changed. Now the id's prefix decides which player receives it, and a genuine failure leaves a
     * diagnostics breadcrumb instead of vanishing.
     */
    @OptIn(UnstableApi::class)
    fun selectAudioTrack(option: AudioTrackOption) {
        // A hand-pick outranks anything automatic for the rest of this source.
        audioDecisionMade = true
        audioPickedByUser = true
        if (option.id.startsWith(VLC_TRACK_PREFIX)) {
            val vlc = vlcPlayer
            val esId = option.id.removePrefix(VLC_TRACK_PREFIX).toIntOrNull()
            if (vlc == null || esId == null || !vlc.selectAudioTrack(esId)) {
                diagnostics.breadcrumb("audioSelect VLC failed id=${option.id} player=${vlc != null}")
                return
            }
            markAudioSelected(option)
            return
        }
        val exo = _player.value
        val tracks = latestTracks
        if (exo == null || tracks == null) {
            diagnostics.breadcrumb("audioSelect Exo unavailable id=${option.id} player=${_player.value != null}")
            return
        }
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val parts = option.id.split(":")
        val gi = parts.getOrNull(0)?.toIntOrNull() ?: return
        val ti = parts.getOrNull(1)?.toIntOrNull() ?: return
        val group = audioGroups.getOrNull(gi) ?: return
        applyExoOverride(exo, group, ti)
        markAudioSelected(option)
    }

    // --- Audio track plumbing (shared by both backends) ---------------------------------------

    /** Flatten ExoPlayer's Tracks into the backend-neutral shape [AudioTrackChoice] reasons over. */
    @OptIn(UnstableApi::class)
    private fun exoAudioTracks(tracks: androidx.media3.common.Tracks): List<AudioTrackChoice.Track> =
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMapIndexed { gi, group ->
                (0 until group.length).map { ti ->
                    val fmt = group.getTrackFormat(ti)
                    AudioTrackChoice.Track(
                        id = "$gi:$ti",
                        language = fmt.language,
                        label = fmt.label,
                        codec = fmt.sampleMimeType,
                        channelCount = fmt.channelCount.takeIf { it != androidx.media3.common.Format.NO_VALUE } ?: 0,
                        isDefault = fmt.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                        // The single most important field: ExoPlayer's own comparator ranks this above
                        // language, so we have to see it to know when English LOST on capability.
                        //
                        // allowExceedsCapabilities = TRUE deliberately. The single-argument overload is
                        // FORMAT_HANDLED only, so it reports a merely EXCEEDS_CAPABILITIES track (a
                        // decoder exists, the format just pushes past its declared limits — commonly
                        // fine in practice) as unsupported. Since this flag gates the automatic
                        // ExoPlayer -> libVLC handoff, the strict form would tear down the working
                        // backend and restart playback on a fallback that was never needed. Hand off
                        // only when the track is genuinely UNHANDLED.
                        supported = group.isTrackSupported(ti, /* allowExceedsCapabilities= */ true),
                        selected = group.isTrackSelected(ti),
                    )
                }
            }

    /** Re-read libVLC's audio ES list and publish it into the SAME flow the picker renders. */
    private fun refreshVlcAudioTracks(vlc: VlcPlayer) {
        val infos = vlc.audioTracks().map { t ->
            AudioTrackChoice.Track(
                id = VLC_TRACK_PREFIX + t.id,
                language = t.language,
                label = t.name,
                codec = t.codec,
                channelCount = t.channels,
                // libVLC 3.x exposes no container disposition on IMedia.Track, so we can't see the
                // DEFAULT flag here. Harmless: it only feeds the last-resort untagged tier, and on
                // that tier a null pick simply leaves libVLC's own default in place.
                isDefault = false,
                // libVLC bundles FFmpeg — if it listed the track, it can decode it. There is no
                // "English exists but is undecodable" case on this backend.
                supported = true,
                selected = t.selected,
            )
        }
        if (infos.isEmpty()) return   // ESAdded for a video/subtitle ES, or the stream closed
        publishAudioTracks(infos)
        // libVLC announces elementary streams INCREMENTALLY — one ESAdded per stream — unlike
        // ExoPlayer's onTracksChanged, which hands over the complete Tracks for the container. Latching
        // on the first callback that showed >= 2 tracks therefore decided from a PARTIAL list: on a
        // 3-track MULTI we could pick "the best of the first two" and never reconsider once the English
        // track finally arrived, which is the very failure this whole change exists to fix.
        // Re-decide whenever the track set GROWS. Re-running is safe: the choice is a pure function of
        // the list, and a user pick always wins (audioPickedByUser).
        if (audioPickedByUser || infos.size <= 1 || infos.size <= audioDecidedTrackCount) return
        audioDecidedTrackCount = infos.size
        audioDecisionMade = true
        val src = _currentSource.value
        val pick = AudioTrackChoice.choose(
            tracks = infos,
            preferredLanguage = preferredAudioCode,
            releaseLanguageLikely = src?.englishLikely != false,
            releaseMultiAudio = src?.multiAudio == true,
        )
        diagnostics.event(
            "player_audio_pick",
            mapOf(
                "backend" to "vlc",
                "want" to preferredAudioCode,
                "reason" to pick.reason.name,
                "confident" to pick.confident.toString(),
                "tracks" to infos.joinToString("|") { it.language ?: "-" },
                "source" to src.diagTag(),
            ),
        )
        val target = pick.trackId ?: return
        if (infos.firstOrNull { it.id == target }?.selected == true) return
        val esId = target.removePrefix(VLC_TRACK_PREFIX).toIntOrNull() ?: return
        if (vlc.selectAudioTrack(esId)) {
            publishAudioTracks(infos.map { it.copy(selected = it.id == target) })
        }
    }

    /** Push a backend's track list to the UI, and derive "what am I hearing?" + "is that right?". */
    private fun publishAudioTracks(infos: List<AudioTrackChoice.Track>) {
        val options = infos.mapIndexed { i, t ->
            AudioTrackOption(
                id = t.id,
                language = AudioTrackChoice.resolveLanguage(t).orEmpty(),
                label = AudioTrackChoice.describe(t, i),
                selected = t.selected,
            )
        }
        _audioTracks.value = options
        val activeIndex = infos.indexOfFirst { it.selected }
        _currentAudio.value = options.getOrNull(activeIndex)
        val want = AudioTrackChoice.normalizeCode(preferredAudioCode)
        val playing = infos.getOrNull(activeIndex)?.let { AudioTrackChoice.resolveLanguage(it) }
        // Warn only when the evidence CONTRADICTS the preference, never when it is merely ABSENT.
        // Most legitimate English releases declare no language at all, so "playing != want" flagged
        // ordinary correct content — a nag on the common case, which trains the user to ignore the one
        // signal that matters. A genuine MULTI whose tracks identify nothing is still flagged, because
        // that arrives as an UNIDENTIFIED/NO_PREFERRED reason rather than as a silent null.
        _audioNeedsAttention.value = !audioPickedByUser && infos.size > 1 &&
            playing != null && want != null && playing != want
    }

    private fun clearAudioTracks() {
        latestTracks = null
        _audioTracks.value = emptyList()
        _currentAudio.value = null
        _audioNeedsAttention.value = false
    }

    private fun markAudioSelected(option: AudioTrackOption) {
        _audioTracks.value = _audioTracks.value.map { it.copy(selected = it.id == option.id) }
        _currentAudio.value = option.copy(selected = true)
        _audioNeedsAttention.value = false   // the user has decided; stop nagging
    }

    @OptIn(UnstableApi::class)
    private fun applyExoAudioOverride(exo: ExoPlayer, trackId: String) {
        val tracks = latestTracks ?: return
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val parts = trackId.split(":")
        val gi = parts.getOrNull(0)?.toIntOrNull() ?: return
        val ti = parts.getOrNull(1)?.toIntOrNull() ?: return
        val group = audioGroups.getOrNull(gi) ?: return
        applyExoOverride(exo, group, ti)
    }

    /** The precise per-track override, so two same-language tracks stay distinguishable (a plain
     *  language preference can't separate "English 5.1" from "English commentary"). */
    @OptIn(UnstableApi::class)
    private fun applyExoOverride(exo: ExoPlayer, group: androidx.media3.common.Tracks.Group, ti: Int) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, ti),
            )
            .build()
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

    private fun buildMediaItem(url: String): ExoMediaItem {
        val builder = ExoMediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subtitleConfigs)
        mimeForDirect(url)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    /** Explicit MIME for a DIRECT URL so HLS (.m3u8) is detected deterministically instead of relying on
     *  Content-Type sniffing (a CDN that mislabels or omits it would otherwise fail to pick the HLS
     *  MediaSource). Torrents return null — they stream from the local server with no extension, so let
     *  ExoPlayer infer. DASH (.mpd) is intentionally omitted: no media3-exoplayer-dash module is bundled. */
    private fun mimeForDirect(url: String): String? {
        if (_currentSource.value?.isDirect != true) return null
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }

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

    /** Fraction of the file (0f..1f) where playback will actually START — the resume point, a Start-over
     *  position, or a failover's saved position — so the engine buffers THERE first instead of the file
     *  head. Mirrors [maybeSeekToResume]'s target. 0f for a fresh from-start play (nothing to skip to). */
    private suspend fun resumeStartFraction(): Float {
        val saved = runCatching {
            libraryRepository.getProgress(mediaId, mediaType, currentSeason, currentEpisode)
        }.getOrNull()
        val durationMs = saved?.durationMs ?: 0L
        val posMs = startOverSessionPositionMs
            ?: saved?.takeIf { !it.isFinished && it.positionMs > 0 }?.positionMs
            ?: 0L
        if (posMs <= 0L || durationMs <= 0L) return 0f
        return (posMs.toFloat() / durationMs).coerceIn(0f, 0.98f)
    }

    private fun maybeSeekToResume(player: Player) {
        if (hasSeekedToResume) return
        hasSeekedToResume = true
        // A Start-over session owns its own position. Initially this is 0; saveProgressNow updates it
        // before any source switch, so later failovers resume the new session without ever consulting
        // the stale pre-Start-over database position.
        startOverSessionPositionMs?.let { sessionPosition ->
            if (sessionPosition > 0L) player.seekTo(sessionPosition) else player.seekTo(0L)
            return
        }
        viewModelScope.launch {
            val saved = libraryRepository.getProgress(mediaId, mediaType, currentSeason, currentEpisode)
            if (saved != null && !saved.isFinished && saved.positionMs > 0) {
                player.seekTo(saved.positionMs)
            }
        }
    }

    /**
     * Kick off sparse scrub-preview sampling once we're actually playing with a known duration. The
     * extractor is idempotent for the same URL, so calling it on every READY re-emit is fine. Skipped
     * for the libVLC fallback — MediaMetadataRetriever can't decode the codecs VLC is there for, so it
     * would only waste prefetch bandwidth.
     */
    private fun maybeStartThumbnails(player: Player) {
        if (usingVlcForSource) return
        val hash = activeInfoHash ?: return
        val url = currentMediaUrl ?: return
        val dur = player.duration.takeIf { it > 0 } ?: return
        val src = _currentSource.value
        if (src?.isDirect == true) {
            // DIRECT (Real-Debrid / free addon / offline download): the torrent engine never started for
            // this source, so fileLength()/filePath() are 0/null — feeding those to the extractor was
            // exactly why RD scrubbing showed empty film-strip slots. Sample from the URL itself with
            // the host's own headers plus a real browser UA (a header-less request gets 403/empty from
            // the same CDNs that forced buildMediaSourceFactory to carry them).
            val hdrs = src.requestHeaders.toMutableMap().apply {
                if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    put("User-Agent", DEFAULT_USER_AGENT)
                }
            }
            thumbnails.start(
                streamUrl = url,
                hash = hash,
                durationMs = dur,
                fileLength = 0L,
                filePath = null,
                headers = hdrs,
                torrentBacked = false,
            )
        } else {
            thumbnails.start(url, hash, dur, torrentStreamer.fileLength(hash), torrentStreamer.filePath(hash))
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

    private fun progressSnapshot(updatedAt: Long = System.currentTimeMillis()): Pair<MediaItem, PlaybackProgress>? {
        // While hopping episodes the (reused) player still holds the OUTGOING episode's position until
        // the new media item is bound — never persist in that window, or it lands under the new key.
        if (switchingEpisode) return null
        // Read from whichever local backend is active — ExoPlayer or the VLC fallback (both Media3
        // [Player]s). Keep using _player while casting so we persist the local position, not the TV's.
        val p: Player = _player.value ?: vlcPlayer ?: return null
        val d = details ?: return null
        val position = p.currentPosition
        if (position <= 0L) return null
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
            updatedAt = updatedAt,
            infoHash = activeInfoHash,
        )
        return item to progress
    }

    private fun saveProgressNow() {
        val (item, progress) = progressSnapshot() ?: return
        if (startOverSessionPositionMs != null) {
            startOverSessionPositionMs = progress.positionMs
        }
        // Use cleanupScope so it still completes if invoked during teardown.
        cleanupScope.launch {
            runCatching { libraryRepository.saveProgress(item, progress) }
        }
    }

    /**
     * Persist an episode hop in causal order. The target gets a zero-position row one millisecond
     * newer than the outgoing row, making "episode 2 started" authoritative even if playback stops
     * before the normal 10-second progress ticker. Natural end-of-file also stores an explicit 100%
     * sentinel because Media3/VLC can report a final position just shy of duration.
     */
    private suspend fun persistEpisodeTransition(
        item: MediaItem,
        fromSeason: Int?,
        fromEpisode: Int?,
        toSeason: Int,
        toEpisode: Int,
        outgoingCompleted: Boolean,
        outgoingSnapshot: PlaybackProgress?,
        outgoingInfoHash: String?,
        targetInfoHash: String?,
    ) {
        val transitionAt = System.currentTimeMillis()
        val outgoing = if (outgoingCompleted && fromSeason != null && fromEpisode != null) {
            PlaybackProgress(
                mediaId = mediaId,
                mediaType = mediaType,
                season = fromSeason,
                episode = fromEpisode,
                positionMs = 1L,
                durationMs = 1L,
                updatedAt = transitionAt,
                infoHash = outgoingInfoHash,
            )
        } else {
            outgoingSnapshot?.copy(updatedAt = transitionAt)
        }
        if (outgoing != null) {
            runCatching { libraryRepository.saveProgress(item, outgoing) }
        }

        val started = PlaybackProgress(
            mediaId = mediaId,
            mediaType = mediaType,
            season = toSeason,
            episode = toEpisode,
            positionMs = 0L,
            durationMs = 0L,
            updatedAt = transitionAt + 1L,
            infoHash = targetInfoHash,
        )
        runCatching { libraryRepository.saveProgress(item, started) }
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

    /** Player title from a downloaded row (offline path — no MediaDetails available). */
    private fun buildOfflineTitle(dl: com.slickstream.core.model.Download): String {
        val s = currentSeason
        val e = currentEpisode
        return if (s != null && e != null) "${dl.title} · S${s}E${e}" else dl.title
    }

    // --- Next-episode prefetch (TV only) ------------------------------------

    /**
     * Warm the next episode once the playhead reaches [NextEpisodeWarm.warmAtMs] — a point DERIVED from
     * the user's own "Up next" card threshold, not a constant that could sit after it.
     *
     * The bar this has to clear: by the time the card appears, the next episode's head AND container
     * index must already be on disk. See [NextEpisodeWarm] for the arithmetic and the worked cases.
     */
    private fun maybeWarmNextEpisode() {
        if (mediaType != MediaType.TV) return
        // Precache the next episode's head even on low-power TV — the user wants instant next-episode
        // starts. The warm is bounded in TIME (one budget covering metadata + head) and in BANDWIDTH
        // (a per-torrent rate cap in the streamer), so it cannot take the pipe from the episode on
        // screen; see TorrentStreamerImpl.prefetch.
        if (_isCasting.value) return                 // remote playback: a phone-side head buffer is useless
        if (!isOnUnmeteredNetwork()) return
        val s = currentSeason ?: return
        val e = currentEpisode ?: return
        if (warmAttemptedFor == s to e) return
        if (android.os.SystemClock.elapsedRealtime() < warmRetryAtMs) return
        // Read from whichever local backend is actually playing. This used to be `_player.value`, the
        // EXOPLAYER flow — but ensureVlcPlayer() sets it to null and keeps the player in `vlcPlayer`, so
        // every source on the libVLC fallback (HEVC/AV1/DTS releases, and every `playable == false`
        // source, which is routed to VLC from the start) never warmed at all. progressSnapshot already
        // reads it this way; the warm was simply never updated to match.
        val p: Player = _player.value ?: vlcPlayer ?: return
        val duration = p.duration
        val position = p.currentPosition
        if (!NextEpisodeWarm.shouldWarmNow(position, duration, endThresholds.value.first)) return

        warmAttemptedFor = s to e                    // claim the slot before the await
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch { warmEpisode(s, e, duration) }
    }

    private suspend fun warmEpisode(fromSeason: Int, fromEpisode: Int, durationMs: Long) {
        val d = details ?: return
        val (nextSeason, nextEpisode) = nextEpisodeCoords(d, fromSeason, fromEpisode) ?: return
        val upNextPct = endThresholds.value.first
        warmPhase = WarmPhase.WARMING
        diagnostics.breadcrumb(
            "warmStart lead=${NextEpisodeWarm.leadMs(durationMs, upNextPct) / 1000}s card=${(upNextPct * 100).toInt()}%",
        )

        val sources = when (val r = sourceRepository.resolve(d, nextSeason, nextEpisode)) {
            is DataResult.Success -> r.data
            is DataResult.Error -> return failWarm("resolve")
        }
        if (sources.isEmpty()) return failWarm("no_sources")
        val best = pickPreferred(sources, networkQualityPreference())

        // A DIRECT (Real-Debrid / file-server) source needs no warm — it plays in about a second — and
        // must NOT be pinned: its identity is syntheticHash(directUrl), a SHA-1 of a URL that RD
        // re-mints on every resolve. The old code recorded that ghost hash as the warm target AND wrote
        // it into the target episode's progress row, permanently poisoning that row's resume hash.
        if (best.isDirect) {
            warmPhase = WarmPhase.READY
            diagnostics.breadcrumb("warmSkip reason=direct")
            return
        }

        // Publish the PIN FIRST — before the same-pack check and before the blocking head-fill — so the
        // hop always starts the release the warm chose, even when there are no bytes to pre-fetch.
        // Without this the pack case published nothing and the hop re-ranked from a fresh resolve,
        // landing on a cold stranger instead of the pack that is already attached and paused in session.
        warmed = NextEpisodeWarm.Warmed(nextSeason, nextEpisode, best)

        val playing = activeInfoHash
        if (best.infoHash.equals(playing, ignoreCase = true)) {
            // SEASON PACK: the next episode is a different FILE inside the torrent already streaming.
            // prefetch() legitimately refuses this (one ActiveTorrent has one selected file), so warm it
            // in place — raise-only, no deadlines, spare capacity only.
            val raised = torrentStreamer.warmPackFile(
                infoHash = playing!!,
                fileIndex = best.fileIndex,
                season = best.expectedSeason ?: nextSeason,
                episode = best.expectedEpisode ?: nextEpisode,
            )
            warmPhase = if (raised) WarmPhase.READY else WarmPhase.IDLE
            if (raised) warmed = warmed?.copy(headReady = true)
            diagnostics.breadcrumb("warmPack raised=$raised")
            return
        }

        // NOTE: there is deliberately no `infoHash in cachedTorrents()` fast-path here any more.
        // cachedTorrents() means "has any on-disk footprint" — hasData() accepts a bare
        // .meta/<hash>.torrent or the empty per-hash directory torrentSavePath() mkdirs at add time — so
        // any earlier add of this hash (a cancelled Details prewarm, a source that was tried and failed
        // over) marked it "cached" with ZERO payload bytes and skipped the warm entirely while still
        // publishing the pin. prefetch() is idempotent and cheap on an already-warm hash: it re-attaches
        // rather than re-adding, and its head loop breaks on the first poll.
        val hash = torrentStreamer.prefetch(best, setOfNotNull(playing))
        if (hash == null) {
            // The magnet is dead (metadata timed out / unparseable). Drop the pin so the hop re-ranks
            // instead of confidently starting a torrent that does not exist, and allow a retry — the
            // next-best source may be fine.
            warmed = null
            return failWarm("prefetch")
        }
        // ASK, don't assume. prefetch() returns its hash as soon as the torrent is attached and keeps
        // returning it when the head loop exhausts its budget, because those partial bytes are still
        // worth protecting. Inferring readiness from a non-null return announced "next episode ready"
        // for a 3-seeder magnet with ~1 MB of head, and then let fastStart launch it with a one-entry
        // source list and nothing to fail over to. The pin is still published either way — the release
        // choice is good and the bytes are real — but only a genuine head+index earns the fast path.
        val ready = torrentStreamer.warmHeadReady(hash)
        warmPhase = if (ready) WarmPhase.READY else WarmPhase.WARMING
        warmed = warmed?.copy(headReady = ready)
        diagnostics.breadcrumb("warmReady=$ready src=${best.diagTag()}")
    }

    /** A warm that produced nothing: release the one-shot slot behind a backoff so the next tick can try
     *  again (the old code burned the slot for the whole episode on the first failure). */
    private fun failWarm(reason: String) {
        warmPhase = WarmPhase.IDLE
        warmAttemptedFor = null
        warmRetryAtMs = android.os.SystemClock.elapsedRealtime() + WARM_RETRY_BACKOFF_MS
        diagnostics.breadcrumb("warmFail reason=$reason")
    }

    /** Next-episode coordinates: same-season next, else episode 1 of the next real season. */
    private suspend fun nextEpisodeCoords(
        d: MediaDetails,
        curSeason: Int,
        curEpisode: Int,
    ): Pair<Int, Int>? {
        val today = com.slickstream.core.model.isoToday()
        // Next episode in THIS season — but only if it has AIRED. A future/unreleased episode (TMDB
        // lists it with a later air date, but no torrent exists) must NOT be the "next": it would
        // auto-advance into a "No streams" error. An unaired next episode = end of the available series.
        val curEps = when (val r = catalogRepository.getEpisodes(mediaId, curSeason)) {
            is DataResult.Success -> r.data
            is DataResult.Error -> return null
        }
        curEps.firstOrNull { it.episodeNumber == curEpisode + 1 }?.let { next ->
            return if (next.hasAired(today)) curSeason to next.episodeNumber else null
        }

        // End of this season → episode 1 of the next real season, if it has aired.
        val nextSeason = d.seasons
            .map { it.seasonNumber }
            .filter { it > curSeason && it >= 1 }
            .minOrNull() ?: return null
        val nextEps = when (val r = catalogRepository.getEpisodes(mediaId, nextSeason)) {
            is DataResult.Success -> r.data
            is DataResult.Error -> return null
        }
        val ep1 = nextEps.firstOrNull { it.episodeNumber == 1 } ?: return null
        return if (ep1.hasAired(today)) nextSeason to ep1.episodeNumber else null
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
            _upNextEpisode.value = null
            return
        }
        val nextCoords = nextEpisodeCoords(d, s, e)
        _hasNext.value = nextCoords != null
        _hasPrevious.value = previousEpisodeCoords(d, s, e) != null
        // Resolve the next episode's title/still for the "Up next" card (getEpisodes is cached).
        _upNextEpisode.value = nextCoords?.let { (ns, ne) -> fetchEpisodeMeta(ns, ne) }
        // End of the AVAILABLE series (last aired episode, no next) → fetch "similar shows" so the
        // player can offer an end-of-series suggestion bar (mirrors the end-of-movie bar). Loaded once,
        // lazily, only here — never during a normal mid-series binge.
        if (nextCoords == null && _similarMovies.value.isEmpty()) {
            viewModelScope.launch { loadSimilarMovies() }
        }
    }

    private suspend fun fetchEpisodeMeta(season: Int, episode: Int): Episode? =
        when (val r = catalogRepository.getEpisodes(mediaId, season)) {
            is DataResult.Success -> r.data.firstOrNull { it.episodeNumber == episode }
            is DataResult.Error -> null
        }

    /**
     * Switch playback to a specific episode of this series: save the current spot, tear down the
     * active stream, re-resolve sources for the target episode and start it — mirroring [load]'s
     * resolve+start path (reset resume seek, reload subtitles, rebuild ExoPlayer via [startSource]).
     */
    fun playEpisode(season: Int, episode: Int) =
        playEpisode(season, episode, outgoingCompleted = false)

    private fun playEpisode(season: Int, episode: Int, outgoingCompleted: Boolean) {
        if (mediaType != MediaType.TV) return
        val d = details ?: return
        if (season == currentSeason && episode == currentEpisode) return

        viewModelScope.launch {
            val fromSeason = currentSeason
            val fromEpisode = currentEpisode
            val outgoingInfoHash = activeInfoHash
            // The warm we can actually redeem for THIS target: keyed to (season, episode) because
            // playEpisode is also the episode-list and previous-episode path. A season pack carries the
            // same btih for every episode, so an unkeyed hash lookup used to "match" a warm computed for
            // a different episode and start a release picked for it.
            val warm = warmed?.takeIf { it.season == season && it.episode == episode }
            // Only a real torrent hash may land in the progress row. A direct source's hash is a SHA-1 of
            // a per-request RD URL, and storing it poisons the row's resume hash forever.
            val targetInfoHash = warm?.source?.takeUnless { it.isDirect }?.infoHash
            // Snapshot BEFORE suppressing saves; persist it synchronously with the target row below.
            // This replaces the old fire-and-forget save that could land after episode 2 had started.
            val outgoingSnapshot = if (outgoingCompleted) null else progressSnapshot()?.second
            // Suppress saves + stop the ticker for the duration of the hop so the outgoing position
            // can't be written under the incoming key; cleared once ensurePlayer binds the new media.
            switchingEpisode = true
            progressTickJob?.cancel()
            persistEpisodeTransition(
                item = d.item,
                fromSeason = fromSeason,
                fromEpisode = fromEpisode,
                toSeason = season,
                toEpisode = episode,
                outgoingCompleted = outgoingCompleted,
                outgoingSnapshot = outgoingSnapshot,
                outgoingInfoHash = outgoingInfoHash,
                targetInfoHash = targetInfoHash,
            )
            bufferWatchdogJob?.cancel()
            // A pending mid-stream downshift belongs to the OUTGOING episode. Left armed, it can fire
            // during the resolve below and start a smaller source of the OLD episode under the new title.
            rebufferWatchdogJob?.cancel()
            _rebuffering.value = null
            stopActiveStream(removeFiles = false)
            // The warm is consumed: clear the pin and re-arm the trigger for the episode we're entering.
            // The warmed BYTES are not discarded — the streamer keeps its eviction protection and the
            // torrent stays paused-in-session, so start() re-attaches and finishes it via StartGate.
            prefetchJob?.cancel()
            warmed = null
            warmAttemptedFor = null
            warmRetryAtMs = 0L
            warmPhase = WarmPhase.IDLE

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

            // FAST PATH — the whole point of precaching. When the pin is for THIS episode and its head
            // (plus index, where the container needs one) is already on disk, start it NOW. Redeeming
            // only after the resolve meant every hop still paid for a full addon fan-out first, and that
            // fan-out is guaranteed cold: ResolveKey is per-episode and the cache TTL is 30 s while the
            // warm ran minutes earlier. That single await was the difference between "~1 second" and
            // "Finding sources…", i.e. between the feature working and appearing not to exist.
            // The real candidate list is still fetched, in the background, purely to fill the source
            // picker and the failover pool.
            NextEpisodeWarm.fastStart(warm, season, episode)?.let { warmSource ->
                _sources.value = listOf(warmSource)
                triedInfoHashes.clear()
                failoverCount = 0
                directFailoverCount = 0
                diagnostics.breadcrumb("warmFastStart src=${warmSource.diagTag()}")
                startSource(warmSource)
                viewModelScope.launch {
                    val full = (sourceRepository.resolve(d, season, episode) as? DataResult.Success)?.data
                    // Only adopt if the user is still on this episode — a hop or a Back can land first.
                    if (full != null && currentSeason == season && currentEpisode == episode) {
                        _sources.value = (
                            listOf(warmSource) +
                                full.filterNot { it.infoHash.equals(warmSource.infoHash, ignoreCase = true) }
                                    .sortedWith(
                                        compareByDescending<StreamSource> { it.isDirect }
                                            .thenByDescending { it.rank },
                                    )
                            )
                    }
                }
                return@launch
            }

            _uiState.value = PlayerUiState.Buffering(0, 0, 0, "Finding sources…")
            val list = when (val r = sourceRepository.resolve(d, currentSeason, currentEpisode)) {
                is DataResult.Success -> r.data
                is DataResult.Error -> {
                    switchingEpisode = false   // hop aborted — don't leave saves suppressed
                    _uiState.value = PlayerUiState.Error(r.message)
                    return@launch
                }
            }
            if (list.isEmpty()) {
                switchingEpisode = false       // hop aborted — don't leave saves suppressed
                _uiState.value = PlayerUiState.Error("No streamable sources found.")
                return@launch
            }
            // DIRECT (file-server) sources first, then torrents by health — mirrors the repository's
            // order. A plain sortedByDescending { rank } buried direct sources below every seeded torrent
            // (directs have null seeders -> rank ≈ quality only), so the user "never saw the HTTPS
            // options". This only orders the PICKER list; the auto-pick uses its own file-server-first
            // logic (pickResumeOrPreferred), so rank-based floors there are untouched.
            _sources.value = list.sortedWith(
                compareByDescending<StreamSource> { it.isDirect }.thenByDescending { it.rank }
            )
            // Fresh episode -> fresh failover budget over the new candidate list.
            triedInfoHashes.clear()
            failoverCount = 0
            directFailoverCount = 0
            // Prefer the release we PRE-WARMED for this episode (its head + index are on disk), then one
            // we already partly downloaded, then a fresh auto-pick.
            //
            // Prefer-if-ELIGIBLE, never force: redeem() re-validates the pin against this device's
            // current quality/decoder gates with the same StreamPicker.isResumeCompatible the saved-
            // resume path uses, and returns null to mean "re-rank normally". Crucially it can also
            // REPLAY the warmed StreamSource object when this fresh resolve has lost that row — the
            // resolve cache TTL is 30 s and the warm ran minutes ago, so this is a second real addon
            // fan-out and one addon timing out was enough to silently discard the whole warm.
            val preference = networkQualityPreference()
            val redeemed = NextEpisodeWarm.redeem(
                warm = warm,
                season = season,
                episode = episode,
                candidates = list,
                maxTier = minOf(preference.maxTier, deviceProfile.maxDisplayTier),
                lowPower = deviceProfile.isLowPower,
            )
            diagnostics.breadcrumb(
                "warmRedeem hit=${redeemed != null} pinned=${warm != null} " +
                    "inFreshList=${warm != null && list.any { it.infoHash.equals(warm.source.infoHash, true) }}",
            )
            // A replayed release that the fresh resolve dropped is not in the picker list — splice it in
            // so the source panel shows what is actually playing.
            if (redeemed != null && list.none { it.infoHash.equals(redeemed.infoHash, ignoreCase = true) }) {
                _sources.value = listOf(redeemed) + _sources.value
            }
            val best = redeemed ?: pickResumeOrPreferred(list, currentSeason, currentEpisode)
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

    /** Natural end-of-file: unlike the manual Next button, the outgoing episode is definitively done. */
    private fun autoAdvanceNextEpisode() {
        val d = details ?: return
        val s = currentSeason ?: return
        val e = currentEpisode ?: return
        viewModelScope.launch {
            val (ns, ne) = nextEpisodeCoords(d, s, e) ?: run {
                saveProgressNow()
                return@launch
            }
            playEpisode(ns, ne, outgoingCompleted = true)
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
        thumbnails.release()
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
        /** Real browser User-Agent for direct (file-server) streams — DefaultHttpDataSource sends NO UA
         *  by default, which header-checking CDNs reject (403). Matches LivePlayerViewModel.DEFAULT_UA. */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
        /** A direct link must reach Playing within this budget or it's failed over (no swarm watchdog
         *  covers direct sources). Short: a live host starts fast; a dead one shouldn't stall the user. */
        const val DIRECT_STARTUP_TIMEOUT_MS = 12_000L

        /** A source that says "Playing" but hasn't painted a frame within this window is black (bad
         *  surface hand-off / failed decode) — surface the source picker so the user can escape. Long
         *  enough that a cold libVLC vout attach on a low-power TV box isn't cut off. */
        const val FIRST_FRAME_TIMEOUT_MS = 8_000L

        /** Auto-pick floor: don't choose a torrent under this many seeders if a healthier one exists. */
        const val MIN_SEEDERS = 8

        /** ExoPlayer's own per-load retry count — keeps re-requesting a not-yet-downloaded range. */
        const val SOURCE_LOAD_RETRIES = 12
        /** Backstop: re-prepare the player up to this many times after a load gives up entirely. */
        /** Id prefix that marks an [AudioTrackOption] as a libVLC elementary stream rather than an
         *  ExoPlayer "group:track" pair — so a list left over from the other backend can never be
         *  applied to the wrong player. */
        const val VLC_TRACK_PREFIX = "vlc:"
        const val MAX_SOURCE_RETRIES = 8
        const val SOURCE_RETRY_BACKOFF_MS = 1_500L

        /** Cap automatic source failovers per title so a wholly-dead title can't loop forever. */
        const val MAX_FAILOVERS = 5
        /** Direct (RD/file-server) links fail instantly + for free, and RD lists many dead/fake [RD+]
         *  entries — so allow walking past a lot of them before giving up (vs the small torrent cap). */
        const val MAX_DIRECT_FAILOVERS = 25
        /** A direct (RD) source whose media duration is under this is a fake/sample (a few-second spam
         *  file), not a real movie/episode — fail over instead of playing it. */
        const val MIN_DIRECT_DURATION_MS = 120_000L
        /** A direct link whose real file (Content-Length) is under this is a fake/sample, not a movie —
         *  rejected by the pre-play HEAD check before the player is even committed. */
        const val MIN_DIRECT_FILE_BYTES = 3L * 1024 * 1024
        val PROGRESSIVE_VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "asf", "mpg", "mpeg", "ts", "m2ts", "mts", "vob", "ogv",
        )
        /** Pre-player buffering wall-clock before the watchdog will consider a source a failure. */
        const val FAILOVER_BUFFER_BUDGET_MS = 75_000L
        /** Watchdog poll cadence while waiting for a source to become playable. */
        const val WATCHDOG_TICK_MS = 3_000L
        /** Head bytes flat this long (while still not playing) = stalled swarm -> fail over. */
        const val WATCHDOG_STALL_MS = 30_000L
        /** READY emitted (player has a URL) but still hasn't reached first frame this long, while bytes
         *  keep flowing = a can't-decode source (missing mp4 moov / scattered head) -> fail over. */
        const val STARTUP_PLAY_TIMEOUT_MS = 70_000L
        /** A mid-stream stall persisting this long = a source whose swarm can't sustain the bitrate ->
         *  auto-downshift to a smaller source (keeping position). */
        const val SUSTAINED_REBUFFER_TIMEOUT_MS = 30_000L
        /** At/above this downloaded fraction the bytes are effectively on disk, so a stall is a decode/
         *  transient issue, NOT bandwidth — never downshift/switch sources (it would re-download). */
        const val NEAR_COMPLETE_FRACTION = 0.5f

        /** Sustained download rate at/above which a mid-stream stall is treated as piece SCATTER or a
         *  decode hiccup (the swarm can clearly keep up), so the downshift-to-smaller watchdog stands
         *  down instead of abandoning a healthy torrent. ~2 MB/s comfortably exceeds any <=1080p bitrate. */
        const val HEALTHY_RATE_BYTES = 2 * 1024 * 1024
        const val PROGRESS_INTERVAL_MS = 10_000L

        /** After a failed next-episode warm (dead magnet / no sources), wait this long before the ticker
         *  may try again. The old code set a one-shot boolean and never retried for the whole episode,
         *  so a single slow-metadata swarm produced a WORSE outcome than no precache at all. */
        const val WARM_RETRY_BACKOFF_MS = 45_000L

        /** Contiguous head gate used by TorrentStreamer before it emits READY. MP4 tail readiness is
         *  represented separately by the streamer's ETA; keeping this identical prevents a 33% bar
         *  from disappearing when a healthy MKV starts. */
        const val PLAYABLE_TARGET_BYTES = 2L * 1024 * 1024
        /** Fixed seconds added to the ETA for the moov/tail fetch + ExoPlayer prepare AFTER the head
         *  fills, so "time to playing" includes the post-download buffering, not just the download. */
        const val PREPARE_MARGIN_SECONDS = 3
        /** Forward cushion (seconds of video) a mid-playback stall must re-buffer before resuming —
         *  the target the rebuffer-badge ETA counts down to. */
        const val REBUFFER_CUSHION_SECONDS = 5
        /** Suggest a smaller stream only after a buffer has dragged on this long (~25 s). */
        const val BUFFERING_NAG_MS = 25_000L
        /** ...and only while the download rate is below this (~150 KB/s) — a genuinely starved swarm. */
        const val BUFFERING_LOW_RATE_BYTES = 150 * 1024
    }
}
