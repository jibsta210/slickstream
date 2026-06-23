package com.slickstream.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One named carousel of catalog cards on the Home/Discover screen.
 */
data class MediaRowUi(
    val title: String,
    val items: List<MediaItem>,
)

/**
 * Immutable snapshot driving [HomeScreen]. The screen renders a [HeroBanner] for [featured],
 * an optional "Continue Watching" row from [continueWatching], then the catalog [rows].
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val featured: MediaItem? = null,
    val continueWatching: List<WatchHistoryItem> = emptyList(),
    val rows: List<MediaRowUi> = emptyList(),
) {
    val isEmpty: Boolean get() = featured == null && rows.isEmpty()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Live "Continue Watching" feed from the local library. */
    private val history: StateFlow<List<WatchHistoryItem>> =
        libraryRepository.observeHistory()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Keep the continue-watching row in sync with the local library at all times.
        history
            .onEach { items -> _uiState.value = _uiState.value.copy(continueWatching = items) }
            .launchIn(viewModelScope)

        load()
    }

    /** Pull-to-refresh / retry entry point. */
    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !isRefresh,
                isRefreshing = isRefresh,
                errorMessage = null,
            )

            // Fan out every catalog call concurrently.
            val trendingDef = asyncCatalog { catalogRepository.getTrending(null) }
            val popularMoviesDef = asyncCatalog { catalogRepository.getPopular(MediaType.MOVIE) }
            val popularTvDef = asyncCatalog { catalogRepository.getPopular(MediaType.TV) }
            val topRatedDef = asyncCatalog { catalogRepository.getTopRated(MediaType.MOVIE) }
            val upcomingDef = asyncCatalog { catalogRepository.getNewAndUpcoming(MediaType.MOVIE) }

            val trending = trendingDef.await()
            val popularMovies = popularMoviesDef.await()
            val popularTv = popularTvDef.await()
            val topRated = topRatedDef.await()
            val upcoming = upcomingDef.await()

            val rows = buildList {
                trending.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Trending", it)) }
                popularMovies.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Popular Movies", it)) }
                popularTv.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Popular TV", it)) }
                topRated.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Top Rated", it)) }
                upcoming.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("New & Upcoming", it)) }
            }

            // Featured hero = best trending entry that has a backdrop to show.
            val featured = trending.itemsOrEmpty()
                .firstOrNull { !it.backdropUrl.isNullOrBlank() }
                ?: trending.itemsOrEmpty().firstOrNull()

            if (rows.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = firstError(trending, popularMovies, popularTv, topRated, upcoming)
                        ?: "Couldn't load content. Check your connection and try again.",
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = null,
                    featured = featured,
                    rows = rows,
                )
            }
        }
    }

    private fun asyncCatalog(
        block: suspend () -> DataResult<List<MediaItem>>,
    ): Deferred<DataResult<List<MediaItem>>> = viewModelScope.async { block() }

    private fun DataResult<List<MediaItem>>.itemsOrEmpty(): List<MediaItem> =
        (this as? DataResult.Success)?.data ?: emptyList()

    private fun firstError(vararg results: DataResult<List<MediaItem>>): String? =
        results.filterIsInstance<DataResult.Error>().firstOrNull()?.message
}
