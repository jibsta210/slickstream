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
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.navigation.NavArg
import dagger.hilt.android.lifecycle.HiltViewModel
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
) {
    val mediaType: MediaType? get() = details?.item?.mediaType
    val isTv: Boolean get() = mediaType == MediaType.TV
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val libraryRepository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

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

    /** Add/remove the current title from favourites. */
    fun toggleFavorite() {
        val item = _uiState.value.details?.item ?: return
        viewModelScope.launch {
            libraryRepository.toggleFavorite(item)
        }
    }
}
