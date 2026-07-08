package com.slickstream.feature.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.Episode
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.Season
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.core.common.DeviceProfile
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.SourceRepository
import com.slickstream.core.repository.TorrentStreamer
import com.slickstream.data.settings.SettingsRepository
import com.slickstream.data.source.StreamPicker
import com.slickstream.navigation.NavArg
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state for the phone details screen. */
data class DetailsUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val details: MediaDetails? = null,
    val similar: List<MediaItem> = emptyList(),
    /** Seasons that actually have episodes (filters out specials with 0). Empty for movies. */
    val seasons: List<Season> = emptyList(),
    val selectedSeasonNumber: Int? = null,
    val episodes: List<Episode> = emptyList(),
    val isLoadingEpisodes: Boolean = false,
    val episodesError: String? = null,
    /** Local watch progress (percent 0f..1f) for the selected season, keyed by episodeNumber. */
    val episodeProgress: Map<Int, Float> = emptyMap(),
    /** Whether this movie is marked watched (always false for TV titles). */
    val isMovieWatched: Boolean = false,
    /** Where the main Play button should go: the in-progress episode (Resume), the next episode after
     *  the last one you finished (Play SxEy), or the first episode if nothing's been watched. */
    val resumeTarget: ResumeTarget? = null,
    /** True when the BEST available release for this title is a CAM/TS (cinema rip) — i.e. there's no
     *  real encode out yet. Surfaced on the details screen so the user knows before pressing Play.
     *  Movies only (resolved during the source prewarm). */
    val onlyCamAvailable: Boolean = false,
) {
    val mediaType: MediaType? get() = details?.item?.mediaType
    val isTv: Boolean get() = mediaType == MediaType.TV
}

/** The episode the Play button resumes/starts, with the label to show on it. season/episode null = movie. */
data class ResumeTarget(val season: Int?, val episode: Int?, val label: String)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val libraryRepository: LibraryRepository,
    private val sourceRepository: SourceRepository,
    private val torrentStreamer: TorrentStreamer,
    private val settingsRepository: SettingsRepository,
    private val deviceProfile: DeviceProfile,
    private val downloadManager: com.slickstream.data.download.DownloadManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** This movie's download state (Downloaded / Downloading % / null). TV downloads are per-episode/
     *  season, shown on the Downloads screen. */
    val downloadState: StateFlow<com.slickstream.core.model.Download?> =
        downloadManager.observe(
            savedStateHandle.get<String>(NavArg.MEDIA_ID)?.toIntOrNull()
                ?: savedStateHandle.get<Int>(NavArg.MEDIA_ID) ?: -1,
            MediaType.fromName(savedStateHandle.get<String>(NavArg.MEDIA_TYPE)),
            null, null,
        ).stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), null)

    /** Download this MOVIE at the user's download quality/size. */
    fun downloadMovie() {
        val item = _uiState.value.details?.item ?: return
        if (item.mediaType == MediaType.MOVIE) downloadManager.download(item)
    }

    /** Download every aired episode of the currently-selected season. */
    fun downloadSeason() {
        val item = _uiState.value.details?.item ?: return
        val season = _uiState.value.selectedSeasonNumber ?: return
        downloadManager.downloadSeason(item, season)
    }

    /** Download ONE episode of the currently-selected season. */
    fun downloadEpisode(season: Int, episode: Int, name: String) {
        val item = _uiState.value.details?.item ?: return
        downloadManager.download(item, season, episode, name)
    }

    /** Single-flight prewarm job — cancelled+replaced on season switch so it can't stack. */
    private var warmJob: Job? = null

    private val mediaType: MediaType =
        MediaType.fromName(savedStateHandle.get<String>(NavArg.MEDIA_TYPE))
    private val mediaId: Int =
        savedStateHandle.get<String>(NavArg.MEDIA_ID)?.toIntOrNull()
            ?: savedStateHandle.get<Int>(NavArg.MEDIA_ID)
            ?: -1

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    /** Whether the current title is favourited — backed by the library Room store. */
    val isFavorite: StateFlow<Boolean> =
        libraryRepository.observeIsFavorite(mediaId, mediaType)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    /** Live download state for every episode of the SELECTED season (episodeNumber -> Download).
     *  Drives the per-episode download buttons and the "Download season" aggregate label — without
     *  this the season button gave ZERO feedback and read as broken. Declared AFTER [_uiState]
     *  (initializer references it at construction). */
    val seasonDownloads: StateFlow<Map<Int, com.slickstream.core.model.Download>> =
        kotlinx.coroutines.flow.combine(downloadManager.downloads, _uiState) { all, ui ->
            val season = ui.selectedSeasonNumber ?: return@combine emptyMap()
            all.filter { it.mediaId == mediaId && it.mediaType == MediaType.TV && it.season == season }
                .mapNotNull { d -> d.episode?.let { it to d } }
                .toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        load()
        // Reactively mirror watch progress into the episode bars / movie-watched flag. This VM survives
        // the player round-trip (it's the back-stack entry's VM), so load()/selectSeason() never re-run
        // on return — without this, a just-watched episode's progress/checkmark stayed stale until a
        // manual refresh. observeHistory() re-emits on every saveProgress upsert, so returning from the
        // player updates the bars immediately.
        libraryRepository.observeHistory()
            .onEach { rows -> applyWatchHistory(rows) }
            .launchIn(viewModelScope)
    }

    /** Most-recent history row for THIS title (observeHistory is ordered newest-first). Drives the
     *  Play button's resume/next-episode target; kept so [updateResumeTarget] can re-run once the
     *  seasons resolve (which it needs to know a season's last episode). */
    private var latestHistory: WatchHistoryItem? = null

    /** Fold the live history feed into [DetailsUiState.episodeProgress] / [DetailsUiState.isMovieWatched]
     *  for THIS title and the currently-selected season. Cheap and idempotent — only writes on change. */
    private fun applyWatchHistory(rows: List<WatchHistoryItem>) {
        val mine = rows.filter { it.media.id == mediaId && it.media.mediaType == mediaType }
        latestHistory = mine.firstOrNull()
        if (mediaType == MediaType.MOVIE) {
            val watched = mine.any { it.progress.isFinished }
            if (watched != _uiState.value.isMovieWatched) {
                _uiState.value = _uiState.value.copy(isMovieWatched = watched)
            }
        } else {
            val season = _uiState.value.selectedSeasonNumber
            if (season != null) {
                val progress = mine.asSequence()
                    .filter { it.progress.season == season && it.progress.episode != null && it.progress.percent > 0f }
                    .associate { it.progress.episode!! to it.progress.percent }
                if (progress != _uiState.value.episodeProgress) {
                    _uiState.value = _uiState.value.copy(episodeProgress = progress)
                }
            }
        }
        updateResumeTarget()
    }

    /** Recompute where Play should go from [latestHistory] + the resolved [seasons]. Called on every
     *  history change AND after the seasons load (it needs episode counts to cross a season boundary). */
    private fun updateResumeTarget() {
        val state = _uiState.value
        val latest = latestHistory
        val target = if (mediaType == MediaType.MOVIE) {
            when {
                latest == null -> ResumeTarget(null, null, "Play")
                latest.progress.isFinished -> ResumeTarget(null, null, "Play again")
                else -> ResumeTarget(null, null, "Resume")
            }
        } else if (latest == null) {
            val s = state.seasons.firstOrNull()?.seasonNumber ?: 1
            ResumeTarget(s, 1, "Play S${s}E1")
        } else {
            val s = latest.progress.season ?: 1
            val e = latest.progress.episode ?: 1
            if (!latest.progress.isFinished) {
                ResumeTarget(s, e, "Resume S${s}E$e")
            } else {
                // Finished -> next episode (cross the season boundary when the seasons are known).
                val epCount = state.seasons.firstOrNull { it.seasonNumber == s }?.episodeCount ?: Int.MAX_VALUE
                when {
                    e < epCount -> ResumeTarget(s, e + 1, "Play S${s}E${e + 1}")
                    else -> state.seasons.firstOrNull { it.seasonNumber > s }
                        ?.let { ResumeTarget(it.seasonNumber, 1, "Play S${it.seasonNumber}E1") }
                        ?: ResumeTarget(s, e, "Replay S${s}E$e")  // whole series watched
                }
            }
        }
        if (target != state.resumeTarget) {
            _uiState.value = _uiState.value.copy(resumeTarget = target)
        }
    }

    fun load() {
        if (mediaId <= 0) {
            _uiState.value = DetailsUiState(
                isLoading = false,
                errorMessage = "Invalid media reference.",
            )
            return
        }
        _uiState.value = DetailsUiState(isLoading = true)
        viewModelScope.launch {
            when (val result = catalogRepository.getDetails(mediaId, mediaType)) {
                is DataResult.Success -> {
                    val details = result.data
                    // Only seasons that contain real episodes; specials (season 0) and empty
                    // placeholder seasons are dropped so the selector stays clean.
                    val playableSeasons = details.seasons
                        .filter { it.episodeCount > 0 && it.seasonNumber > 0 }
                        .sortedBy { it.seasonNumber }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = null,
                        details = details,
                        seasons = playableSeasons,
                    )
                    // Now that seasons (with episode counts) are known, derive where Play should go.
                    updateResumeTarget()
                    // Default-select the season you were LAST WATCHING (so reopening a show you're deep
                    // into doesn't dump you back on Season 1), falling back to the first season. Read a
                    // history snapshot here because the parallel observeHistory() collector may not have
                    // populated latestHistory yet.
                    if (details.item.mediaType == MediaType.TV) {
                        val lastWatchedSeason = runCatching {
                            libraryRepository.observeHistory().first()
                                .firstOrNull { it.media.id == mediaId && it.media.mediaType == mediaType }
                                ?.progress?.season
                        }.getOrNull()?.takeIf { s -> playableSeasons.any { it.seasonNumber == s } }
                        val initialSeason = lastWatchedSeason ?: playableSeasons.firstOrNull()?.seasonNumber
                        initialSeason?.let { selectSeason(it) }
                    } else {
                        // Movie: reflect any saved watched state, then prewarm the torrent NOW (while
                        // the user reads the synopsis) so Play hits a metadata-cache + 2MB head
                        // instead of a cold 30-60s fetch.
                        refreshMovieWatched()
                        warmSource(details, season = null, episode = null)
                    }
                    loadSimilar()
                }

                is DataResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    private fun loadSimilar() {
        viewModelScope.launch {
            when (val result = catalogRepository.getSimilar(mediaId, mediaType)) {
                is DataResult.Success ->
                    _uiState.value = _uiState.value.copy(similar = result.data)
                is DataResult.Error -> Unit // similar is non-critical; ignore failures
            }
        }
    }

    /**
     * Prewarm the torrent for this title/episode while the user is still on the details screen, so
     * pressing Play hits a metadata cache + an already-buffered 2 MB head instead of a cold 30-60s
     * fetch. Picks the SAME source the player will ([StreamPicker]); single-flight + cancel-on-switch.
     * Safe even on low-power TV: nothing is decoding yet, so it doesn't trip the playback freeze gate.
     */
    private fun warmSource(d: MediaDetails, season: Int?, episode: Int?) {
        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            runCatching {
                val list = when (val r = sourceRepository.resolve(d, season, episode)) {
                    is DataResult.Success -> r.data
                    is DataResult.Error -> return@launch
                }
                if (list.isEmpty()) return@launch
                val settings = settingsRepository.current()
                val best = StreamPicker.pick(list, settings.wifiQuality.maxTier, settings.streamSize, deviceProfile.isLowPower)
                    ?: return@launch
                // The picker sinks CAMs below real encodes, so a CAM only wins when nothing better exists
                // — flag it for the details UI ("CAM" badge: the only version out is a cinema rip).
                if (best.isCam != _uiState.value.onlyCamAvailable) {
                    _uiState.value = _uiState.value.copy(onlyCamAvailable = best.isCam)
                }
                torrentStreamer.prefetch(best)
            }
        }
    }

    override fun onCleared() {
        warmJob?.cancel()
        super.onCleared()
    }

    /** Pick a season and lazy-load its episodes. No-op if it is already selected & loaded. */
    fun selectSeason(seasonNumber: Int) {
        val current = _uiState.value
        if (current.selectedSeasonNumber == seasonNumber &&
            current.episodes.isNotEmpty() &&
            current.episodesError == null
        ) {
            return
        }
        _uiState.value = current.copy(
            selectedSeasonNumber = seasonNumber,
            isLoadingEpisodes = true,
            episodesError = null,
            episodes = emptyList(),
            episodeProgress = emptyMap(),
        )
        viewModelScope.launch {
            when (val result = catalogRepository.getEpisodes(mediaId, seasonNumber)) {
                is DataResult.Success -> {
                    // Guard against a stale response if the user switched seasons quickly.
                    if (_uiState.value.selectedSeasonNumber == seasonNumber) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingEpisodes = false,
                            episodes = result.data,
                            episodesError = null,
                        )
                        // Await the progress map BEFORE picking the prewarm target — reading
                        // _uiState.episodeProgress here raced the async DB load (the map was just
                        // reset to empty), so the prewarm always warmed episode 1 while Play
                        // targeted the user's actual mid-season episode cold.
                        val progress = loadEpisodeProgress(seasonNumber, result.data)
                        // Prewarm the first not-yet-finished episode of this season so Play is fast.
                        val d = _uiState.value.details
                        if (d != null) {
                            val firstUnwatched = result.data
                                .firstOrNull { (progress[it.episodeNumber] ?: 0f) < 0.92f }
                                ?: result.data.firstOrNull()
                            warmSource(d, season = seasonNumber, episode = firstUnwatched?.episodeNumber ?: 1)
                        }
                    }
                }

                is DataResult.Error -> {
                    if (_uiState.value.selectedSeasonNumber == seasonNumber) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingEpisodes = false,
                            episodesError = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Look up local resume progress for each episode of [seasonNumber] and publish it as a
     * episodeNumber -> percent map. Runs after a season's episodes resolve (and on season switch)
     * so returning to details reflects newly-watched episodes. Stale if the user has since
     * switched seasons, so the result is dropped unless the season still matches.
     */
    private suspend fun loadEpisodeProgress(seasonNumber: Int, episodes: List<Episode>): Map<Int, Float> {
        val progress = buildMap {
            for (ep in episodes) {
                val saved = libraryRepository.getProgress(
                    mediaId,
                    MediaType.TV,
                    ep.seasonNumber,
                    ep.episodeNumber,
                )
                if (saved != null && saved.percent > 0f) {
                    put(ep.episodeNumber, saved.percent)
                }
            }
        }
        if (_uiState.value.selectedSeasonNumber == seasonNumber) {
            _uiState.value = _uiState.value.copy(episodeProgress = progress)
        }
        return progress
    }

    /** Add/remove the current title from favourites. */
    fun toggleFavorite() {
        val item = _uiState.value.details?.item ?: return
        viewModelScope.launch {
            libraryRepository.toggleFavorite(item)
        }
    }

    // --- Mark watched / unwatched ---

    /** Mark an episode fully watched, then refresh the per-episode progress bars. */
    fun markWatched(season: Int, episode: Int) {
        val item = _uiState.value.details?.item ?: return
        viewModelScope.launch {
            libraryRepository.markWatched(item, season, episode)
            refreshEpisodeProgress()
        }
    }

    /** Clear an episode's watched/in-progress row, then refresh the per-episode progress bars. */
    fun markUnwatched(season: Int, episode: Int) {
        val item = _uiState.value.details?.item ?: return
        viewModelScope.launch {
            libraryRepository.markUnwatched(item, season, episode)
            refreshEpisodeProgress()
        }
    }

    /** Mark the current movie fully watched. */
    fun markMovieWatched() {
        val item = _uiState.value.details?.item ?: return
        viewModelScope.launch {
            libraryRepository.markWatched(item, season = null, episode = null)
            refreshMovieWatched()
        }
    }

    /** Clear the current movie's watched/in-progress row. */
    fun markMovieUnwatched() {
        val item = _uiState.value.details?.item ?: return
        viewModelScope.launch {
            libraryRepository.markUnwatched(item, season = null, episode = null)
            refreshMovieWatched()
        }
    }

    /** Re-read per-episode progress for the currently selected season (after a watched toggle). */
    private fun refreshEpisodeProgress() {
        val season = _uiState.value.selectedSeasonNumber ?: return
        val episodes = _uiState.value.episodes
        if (episodes.isNotEmpty()) {
            viewModelScope.launch { loadEpisodeProgress(season, episodes) }
        }
    }

    /** Re-read the movie's saved row and publish its watched state. */
    private fun refreshMovieWatched() {
        viewModelScope.launch {
            val saved = libraryRepository.getProgress(mediaId, mediaType, season = null, episode = null)
            _uiState.value = _uiState.value.copy(isMovieWatched = saved?.isFinished == true)
        }
    }
}
