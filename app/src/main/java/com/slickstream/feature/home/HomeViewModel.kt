package com.slickstream.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.FavoriteItem
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    /** Favourite shows you're CAUGHT UP on that just got a fresh episode (within the last few weeks).
     *  Each row's [WatchHistoryItem.progress] points at the new episode (0%), ready to play. */
    val newEpisodes: List<WatchHistoryItem> = emptyList(),
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

    /** Coarse "what's been watched" signal: a key per (show, season, episode, finished?) row. It changes
     *  only when an episode is added/removed or its FINISHED state flips — NOT on every position tick — so
     *  the new-episodes resolve (which fans out to the catalog) doesn't re-run every ~10s during playback. */
    private val watchedSignal: Flow<Set<String>> =
        libraryRepository.observeHistory()
            .map { rows ->
                rows.mapTo(HashSet()) {
                    "${it.media.id}:${it.progress.season}:${it.progress.episode}:${it.progress.isFinished}"
                }
            }
            .distinctUntilChanged()

    /**
     * "New Episodes" rail: a favourite show you're CAUGHT UP on just got a fresh episode. Resolved from
     * the active profile's favourites + TMDB's last_episode_to_air, re-run when favourites change or when
     * an episode is finished/added (via [watchedSignal] — the actual progress is read per-show below).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val newEpisodes: StateFlow<List<WatchHistoryItem>> =
        combine(libraryRepository.observeFavorites(), watchedSignal) { favs, _ -> favs }
            .mapLatest { favs -> resolveNewEpisodes(favs) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val newEpisodeIds: Flow<Set<Int>> = newEpisodes.map { list -> list.mapTo(HashSet()) { it.media.id } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val continueWatching: StateFlow<List<WatchHistoryItem>> =
        combine(rawHistory, endThresholds, newEpisodeIds) { rows, th, newIds -> Triple(rows, th, newIds) }
            .mapLatest { (rows, th, newIds) ->
                // A caught-up favourite with a fresh episode lives on the "New Episodes" rail, NOT here —
                // exclude those show ids so the same title isn't shown twice.
                resolveContinueWatching(rows, th.first, th.second).filterNot { it.media.id in newIds }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private suspend fun resolveNewEpisodes(favorites: List<FavoriteItem>): List<WatchHistoryItem> = coroutineScope {
        val today = isoToday()
        val cutoff = isoDaysAgo(NEW_EPISODE_WINDOW_DAYS)
        favorites
            .filter { it.media.mediaType == MediaType.TV }
            .map { fav -> async { runCatching { resolveNewEpisodeFor(fav.media, today, cutoff) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * Is [show]'s latest aired episode a "new drop" the user is ready for? Yes only when it aired within
     * the window, the user hasn't started it, AND they're CAUGHT UP (finished the episode right before it,
     * or it's the series premiere). No point flagging S3E8 to someone still on S1E1 — that's a backlog,
     * which Continue Watching already handles.
     */
    private suspend fun resolveNewEpisodeFor(show: MediaItem, today: String, cutoff: String): WatchHistoryItem? {
        val details = (catalogRepository.getDetails(show.id, MediaType.TV) as? DataResult.Success)?.data ?: return null
        val last = details.lastEpisodeToAir ?: return null
        val air = last.airDate ?: return null
        if (air > today || air < cutoff) return null                         // not aired yet, or too old to be "new"
        val ls = last.seasonNumber
        val le = last.episodeNumber
        // Already started/watched it → it's a Continue-Watching item, not a fresh surprise.
        if (libraryRepository.getProgress(show.id, MediaType.TV, ls, le) != null) return null
        val pred = predecessorEpisode(details, ls, le)
        val caughtUp = pred == null ||
            libraryRepository.getProgress(show.id, MediaType.TV, pred.first, pred.second)?.isFinished == true
        if (!caughtUp) return null
        return WatchHistoryItem(
            media = show,
            progress = PlaybackProgress(
                mediaId = show.id, mediaType = MediaType.TV,
                season = ls, episode = le,
                positionMs = 0L, durationMs = 0L, updatedAt = 0L,
            ),
        )
    }

    /** The episode immediately before [season]/[episode]: same-season -1, else the last episode of the
     *  previous real season (episode counts from [MediaDetails.seasons]). Null at the series premiere. */
    private fun predecessorEpisode(details: MediaDetails, season: Int, episode: Int): Pair<Int, Int>? {
        if (episode > 1) return season to (episode - 1)
        val prevSeason = details.seasons.map { it.seasonNumber }.filter { it in 1 until season }.maxOrNull() ?: return null
        val count = details.seasons.firstOrNull { it.seasonNumber == prevSeason }?.episodeCount ?: return null
        return if (count > 0) prevSeason to count else null
    }

    private fun isoDaysAgo(days: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -days)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }

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
        // Keep the continue-watching + new-episodes rows in sync with the library + settings at all times
        // (atomic update so the two collectors can't clobber each other's slice of the state).
        continueWatching
            .onEach { items -> _uiState.update { it.copy(continueWatching = items) } }
            .launchIn(viewModelScope)
        newEpisodes
            .onEach { items -> _uiState.update { it.copy(newEpisodes = items) } }
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
            // Personalized "Because You Watched" rail from THIS profile's history+favourites genre mix
            // (concurrent; null when there isn't enough signal yet).
            val personalizedDef = viewModelScope.async { runCatching { buildPersonalizedRow() }.getOrNull() }

            val trending = trendingDef.await()
            val popularMovies = popularMoviesDef.await()
            val popularTv = popularTvDef.await()
            val topRated = topRatedDef.await()
            val upcoming = upcomingDef.await()
            val personalized = personalizedDef.await()

            val rows = buildList {
                // Right after Continue Watching (rendered separately) — the prime slot.
                personalized?.let { add(it) }
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

    /**
     * "Because You Watched" — a personalized rail from the ACTIVE profile's own history + favourites.
     * Take the dominant media type, the most-watched genre WITHIN that type (so movie/TV genre ids never
     * cross), fetch that genre, drop anything already seen, and only surface it when there's real signal
     * (>=4 fresh titles). Re-computed on every load() (i.e. on profile switch). Null = not enough yet.
     */
    private suspend fun buildPersonalizedRow(): MediaRowUi? {
        val history = runCatching { libraryRepository.observeHistory().first() }.getOrDefault(emptyList())
        val favs = runCatching { libraryRepository.observeFavorites().first() }.getOrDefault(emptyList())
        val watched = history.map { it.media } + favs.map { it.media }
        if (watched.isEmpty()) return null
        val type = watched.groupingBy { it.mediaType }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val topGenre = watched.asSequence()
            .filter { it.mediaType == type }
            .flatMap { it.genreIds.asSequence() }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: return null
        val items = (catalogRepository.getByGenre(type, topGenre) as? DataResult.Success)?.data ?: return null
        val seen = watched.mapTo(HashSet()) { it.id }
        val fresh = items.filterNot { it.id in seen }
        if (fresh.size < 4) return null
        val title = GENRE_NAMES[topGenre]?.let { "More $it" } ?: "Because You Watched"
        return MediaRowUi(title, fresh)
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

        /** How recently a favourite's latest episode must have aired to count as a "new" drop. */
        const val NEW_EPISODE_WINDOW_DAYS = 21

        /** TMDB genre id -> display name, for the "More <genre>" personalized rail title. Covers both
         *  movie and TV genre id spaces (they overlap but differ for a few). */
        val GENRE_NAMES: Map<Int, String> = mapOf(
            28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
            99 to "Documentaries", 18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History",
            27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi",
            53 to "Thrillers", 10752 to "War", 37 to "Westerns",
            10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News", 10764 to "Reality",
            10765 to "Sci-Fi & Fantasy", 10766 to "Soaps", 10767 to "Talk", 10768 to "War & Politics",
        )
    }
}
