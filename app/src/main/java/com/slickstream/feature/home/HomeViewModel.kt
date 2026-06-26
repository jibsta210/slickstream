package com.slickstream.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.core.model.hasAired
import com.slickstream.core.model.isoToday
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.ProfileRepository
import com.slickstream.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
    private val settingsRepository: SettingsRepository,
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
    // Raw history, deduped to the most-recent row per title. ALL the finished/advance logic happens in
    // [resolveContinueWatching] below — it needs the live Settings thresholds AND the catalog (to know
    // whether a next episode even exists), so it can't be a pure .map here.
    private val rawHistory: Flow<List<WatchHistoryItem>> =
        libraryRepository.observeHistory()
            .map { rows -> rows.asSequence().distinctBy { it.media.id to it.media.mediaType }.toList() }

    /** (episode "up next" threshold, movie "similar bar" threshold) as 0..1 fractions — the SAME
     *  settings that drive the in-player next-episode / similar-titles cards, so the rail advances
     *  exactly when those cards say a title is done. */
    private val endThresholds: Flow<Pair<Float, Float>> =
        settingsRepository.settings
            .map { (it.upNextPercent / 100f) to (it.movieBarPercent / 100f) }
            .distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val continueWatching: StateFlow<List<WatchHistoryItem>> =
        combine(rawHistory, endThresholds) { rows, th -> rows to th }
            .mapLatest { (rows, th) -> resolveContinueWatching(rows, th.first, th.second) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Turn raw history into the Continue-Watching rail:
     *  - below the threshold → resume the SAME episode/movie in place;
     *  - a TV episode at/over the up-next threshold → roll forward to the NEXT AIRED episode (fresh, 0%),
     *    or DROP the show entirely when there is no next aired episode (you finished it / are caught up) —
     *    so the rail never offers a non-existent episode;
     *  - a movie at/over the movie threshold → drop (genuinely done).
     */
    private suspend fun resolveContinueWatching(
        rows: List<WatchHistoryItem>,
        tvThreshold: Float,
        movieThreshold: Float,
    ): List<WatchHistoryItem> = coroutineScope {
        rows.map { h ->
            async {
                when (h.media.mediaType) {
                    MediaType.MOVIE -> if (h.progress.percent >= movieThreshold) null else h
                    MediaType.TV -> resolveShowRow(h, tvThreshold)
                    else -> h
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolveShowRow(h: WatchHistoryItem, threshold: Float): WatchHistoryItem? {
        if (h.progress.percent < threshold) return h          // still watching this episode
        val s = h.progress.season ?: return h
        val e = h.progress.episode ?: return h
        // Past the threshold: advance to the next AIRED episode, or drop if there isn't one. A catalog
        // hiccup must NOT silently drop the show, so fall back to keeping it as-is.
        val next = try {
            nextAiredEpisode(h.media.id, s, e)
        } catch (t: Throwable) {
            return h
        }
        next ?: return null                                   // no next aired episode -> off the rail
        return h.copy(
            progress = h.progress.copy(season = next.first, episode = next.second, positionMs = 0L),
        )
    }

    /**
     * Next AIRED episode after [curSeason]/[curEpisode]: same-season next, else episode 1 of the next
     * real season — but only if it has aired. Mirrors the in-player navigation so the rail and the
     * player agree on what "next" is (and on when a series has ended).
     */
    private suspend fun nextAiredEpisode(showId: Int, curSeason: Int, curEpisode: Int): Pair<Int, Int>? {
        val today = isoToday()
        val curEps = (catalogRepository.getEpisodes(showId, curSeason) as? DataResult.Success)?.data ?: return null
        curEps.firstOrNull { it.episodeNumber == curEpisode + 1 }?.let { next ->
            return if (next.hasAired(today)) curSeason to next.episodeNumber else null
        }
        val details = (catalogRepository.getDetails(showId, MediaType.TV) as? DataResult.Success)?.data ?: return null
        val nextSeason = details.seasons.map { it.seasonNumber }.filter { it > curSeason && it >= 1 }.minOrNull()
            ?: return null
        val nextEps = (catalogRepository.getEpisodes(showId, nextSeason) as? DataResult.Success)?.data ?: return null
        val ep1 = nextEps.firstOrNull { it.episodeNumber == 1 } ?: return null
        return if (ep1.hasAired(today)) nextSeason to ep1.episodeNumber else null
    }

    /** Whether the active profile is a kids profile — branches Home between default and kid rows. */
    private var kidsMode: Boolean = false

    init {
        // Keep the continue-watching row in sync with the local library + settings at all times.
        continueWatching
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
