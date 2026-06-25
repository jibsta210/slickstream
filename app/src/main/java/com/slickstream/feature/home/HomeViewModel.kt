package com.slickstream.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Live "Continue Watching" feed from the local library — the user's full watch history, scrollable,
     * not just the few in-progress items. We collapse to the single most-recent row per title (the
     * underlying flow is ordered most-recent-first) but no longer drop a title once an episode is
     * finished: a SHOW you're working through must STAY on the rail pointing at the next episode (it
     * used to vanish the moment you finished one). A finished MOVIE is genuinely done, so it leaves.
     */
    private val history: StateFlow<List<WatchHistoryItem>> =
        libraryRepository.observeHistory()
            .map { rows ->
                rows.asSequence()
                    .filterNot { it.progress.isFinished && it.media.mediaType == MediaType.MOVIE }
                    .distinctBy { it.media.id to it.media.mediaType }
                    .toList()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether the active profile is a kids profile — branches Home between default and kid rows. */
    private var kidsMode: Boolean = false

    init {
        // Keep the continue-watching row in sync with the local library at all times.
        history
            .onEach { items -> _uiState.value = _uiState.value.copy(continueWatching = items) }
            .launchIn(viewModelScope)

        // Re-load Home whenever the active profile's identity OR kids-ness changes (a switch to a
        // kids profile swaps in the family rows; switching back restores the defaults unchanged).
        profileRepository.activeProfile
            .map { it?.id to (it?.isKids ?: false) }
            .distinctUntilChanged()
            .onEach { (_, isKids) ->
                kidsMode = isKids
                load()
            }
            .launchIn(viewModelScope)
    }

    /** Pull-to-refresh / retry entry point. */
    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean = false) {
        if (kidsMode) loadKids(isRefresh) else loadDefault(isRefresh)
    }

    private fun loadDefault(isRefresh: Boolean) {
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

            publish(rows, featured, firstError(trending, popularMovies, popularTv, topRated, upcoming), isRefresh)
        }
    }

    /**
     * Kid-focused Home: family/animation movie + kids/family TV rows from the genre discover
     * endpoint (which already sends include_adult=false). The hero comes from the first non-empty
     * kid row so the banner is also family-safe.
     */
    private fun loadKids(isRefresh: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !isRefresh,
                isRefreshing = isRefresh,
                errorMessage = null,
            )

            val familyMoviesDef = asyncCatalog { catalogRepository.getByGenre(MediaType.MOVIE, GENRE_FAMILY) }
            val animationDef = asyncCatalog { catalogRepository.getByGenre(MediaType.MOVIE, GENRE_ANIMATION) }
            val kidsTvDef = asyncCatalog { catalogRepository.getByGenre(MediaType.TV, GENRE_TV_KIDS) }
            val familyTvDef = asyncCatalog { catalogRepository.getByGenre(MediaType.TV, GENRE_FAMILY) }

            val familyMovies = familyMoviesDef.await()
            val animation = animationDef.await()
            val kidsTv = kidsTvDef.await()
            val familyTv = familyTvDef.await()

            val rows = buildList {
                familyMovies.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Family Movies", it)) }
                animation.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Animation", it)) }
                kidsTv.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Kids TV", it)) }
                familyTv.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Family Shows", it)) }
            }

            // Hero from a kid row — prefer one with a backdrop, across all kid rows.
            val featured = rows.asSequence().flatMap { it.items.asSequence() }
                .firstOrNull { !it.backdropUrl.isNullOrBlank() }
                ?: rows.firstOrNull()?.items?.firstOrNull()

            publish(rows, featured, firstError(familyMovies, animation, kidsTv, familyTv), isRefresh)
        }
    }

    /** Commit a freshly loaded set of rows (or an error when nothing loaded) to the UI state. */
    private fun publish(
        rows: List<MediaRowUi>,
        featured: MediaItem?,
        error: String?,
        isRefresh: Boolean,
    ) {
        if (rows.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isRefreshing = false,
                featured = null,
                rows = emptyList(),
                errorMessage = error
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

    private fun asyncCatalog(
        block: suspend () -> DataResult<List<MediaItem>>,
    ): Deferred<DataResult<List<MediaItem>>> = viewModelScope.async { block() }

    private fun DataResult<List<MediaItem>>.itemsOrEmpty(): List<MediaItem> =
        (this as? DataResult.Success)?.data ?: emptyList()

    private fun firstError(vararg results: DataResult<List<MediaItem>>): String? =
        results.filterIsInstance<DataResult.Error>().firstOrNull()?.message

    private companion object {
        // TMDB genre ids used to build the kids Home.
        const val GENRE_FAMILY = 10751
        const val GENRE_ANIMATION = 16
        const val GENRE_TV_KIDS = 10762
    }
}
