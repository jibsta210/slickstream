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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    val mediaType: MediaType? get() = details?.item?.mediaType
    val isTv: Boolean get() = mediaType == MediaType.TV
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val libraryRepository: LibraryRepository,
    private val sourceRepository: SourceRepository,
    private val torrentStreamer: TorrentStreamer,
    private val settingsRepository: SettingsRepository,
    private val deviceProfile: DeviceProfile,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

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

    init {
        load()
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
                    // Default-select the first season for TV shows and eagerly load it.
                    if (details.item.mediaType == MediaType.TV) {
                        playableSeasons.firstOrNull()?.let { selectSeason(it.seasonNumber) }
                    } else {
                        // Movie: prewarm the torrent NOW (while the user reads the synopsis) so Play
                        // hits a metadata-cache + 2MB head instead of a cold 30-60s fetch.
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
                        loadEpisodeProgress(seasonNumber, result.data)
                        // Prewarm the first not-yet-finished episode of this season so Play is fast.
                        val d = _uiState.value.details
                        if (d != null) {
                            val firstUnwatched = result.data
                                .firstOrNull { (_uiState.value.episodeProgress[it.episodeNumber] ?: 0f) < 0.92f }
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
    private fun loadEpisodeProgress(seasonNumber: Int, episodes: List<Episode>) {
        viewModelScope.launch {
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
        }
    }

    /** Add/remove the current title from favourites. */
    fun toggleFavorite() {
        val item = _uiState.value.details?.item ?: return
        viewModelScope.launch {
            libraryRepository.toggleFavorite(item)
        }
    }
}
