package com.slickstream.feature.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.Genre
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.feature.home.HomeUiState
import com.slickstream.feature.home.MediaRowUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the dedicated Movies / TV tabs. Same shape as the Home screen ([HomeUiState] +
 * [MediaRowUi]) but scoped to a single [MediaType], with Trending / Popular / Top-Rated / New
 * rows plus a few genre carousels so each tab feels like a full catalog.
 */
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val profileRepository: com.slickstream.core.repository.ProfileRepository,
) : ViewModel() {

    /** Bumped by retry/refresh so it rotates in different shelves. */
    private var salt = 0

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** The full genre list for the current tab, surfaced as browseable category chips. */
    private val _genres = MutableStateFlow<List<Genre>>(emptyList())
    val genres: StateFlow<List<Genre>> = _genres.asStateFlow()

    private var loadedType: MediaType? = null

    init {
        // Re-fetch on a profile identity/kids change: the repository gates content by the ACTIVE
        // profile, so rows loaded for an adult profile must not survive a switch to a kids profile
        // (this VM outlives the screen on the nav back stack). drop(1) skips the startup emission —
        // the screen's own load() covers the first fetch.
        profileRepository.activeProfile
            .map { it?.id to (it?.isKids ?: false) }
            .distinctUntilChanged()
            .drop(1)
            .onEach { loadedType?.let(::fetch) }
            .launchIn(viewModelScope)
    }

    /** Idempotent: the screen calls this with its [MediaType]; re-loads only on a type change. */
    fun load(mediaType: MediaType) {
        if (loadedType == mediaType) return
        loadedType = mediaType
        fetch(mediaType)
    }

    fun retry() {
        salt++
        loadedType?.let { fetch(it) }
    }

    private fun fetch(mediaType: MediaType) {
        if (profileRepository.activeProfile.value?.isKids != true) fetchShelves(mediaType) else fetchKids(mediaType)
    }

    /** Adult Movies/TV tab: its own new releases, a rotating set of shelves, then genre carousels on
     *  rotating pages — all de-duplicated against each other (core/catalog/Shelves). */
    private fun fetchShelves(mediaType: MediaType) {
        viewModelScope.launch {
            _state.value = HomeUiState(isLoading = true)
            val today = com.slickstream.core.catalog.Shelves.Date.today()
            val genresDef = async { catalog.getGenres(mediaType) }
            val allGenres = (genresDef.await() as? DataResult.Success)?.data.orEmpty()
            _genres.value = allGenres
            val genreShelves = com.slickstream.core.catalog.Shelves
                .rotate(allGenres, 5, today.epochDay + salt + mediaType.ordinal)
                .map { com.slickstream.core.catalog.Shelves.genre(mediaType, it.id, it.name) }
            val plan = com.slickstream.core.catalog.Shelves.tab(mediaType, today, salt) + genreShelves
            val loaded = com.slickstream.core.catalog.ShelfLoader.load(catalog, plan, today, salt)
            val rows = loaded.rows.map { (shelf, items) -> MediaRowUi(shelf.title, items) }
            val top = rows.firstOrNull()?.items.orEmpty()
            val featured = top.firstOrNull { !it.backdropUrl.isNullOrBlank() } ?: top.firstOrNull()
            _state.value = if (rows.isEmpty()) {
                HomeUiState(
                    isLoading = false,
                    errorMessage = loaded.firstError ?: "Couldn't load content. Check your connection and try again.",
                )
            } else {
                HomeUiState(isLoading = false, featured = featured, rows = rows)
            }
        }
    }

    /** Kids profile: the kid-safe rows (the repository caps every call by rating). */
    private fun fetchKids(mediaType: MediaType) {
        viewModelScope.launch {
            _state.value = HomeUiState(isLoading = true)

            val trendingDef = async { catalog.getTrending(mediaType) }
            val popularDef = async { catalog.getPopular(mediaType) }
            val topRatedDef = async { catalog.getTopRated(mediaType) }
            val newDef = async { catalog.getNewAndUpcoming(mediaType) }
            val genresDef = async { catalog.getGenres(mediaType) }

            val trending = trendingDef.await()
            val popular = popularDef.await()
            val topRated = topRatedDef.await()
            val newest = newDef.await()
            val allGenres = (genresDef.await() as? DataResult.Success)?.data.orEmpty()
            _genres.value = allGenres
            val genres = allGenres.take(4)

            val genreRows = genres
                .map { g -> g.name to async { catalog.getByGenre(mediaType, g.id) } }
                .map { (name, def) -> name to def.await().itemsOrEmpty() }

            val isMovie = mediaType == MediaType.MOVIE
            val rows = buildList {
                trending.itemsOrEmpty().let {
                    if (it.isNotEmpty()) add(MediaRowUi(if (isMovie) "Trending Movies" else "Trending Shows", it))
                }
                popular.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Popular", it)) }
                topRated.itemsOrEmpty().let { if (it.isNotEmpty()) add(MediaRowUi("Top Rated", it)) }
                newest.itemsOrEmpty().let {
                    if (it.isNotEmpty()) add(MediaRowUi(if (isMovie) "Now Playing" else "On The Air", it))
                }
                genreRows.forEach { (name, items) -> if (items.isNotEmpty()) add(MediaRowUi(name, items)) }
            }

            val featured = trending.itemsOrEmpty().firstOrNull { !it.backdropUrl.isNullOrBlank() }
                ?: trending.itemsOrEmpty().firstOrNull()

            _state.value = if (rows.isEmpty()) {
                HomeUiState(
                    isLoading = false,
                    errorMessage = firstError(trending, popular, topRated, newest)
                        ?: "Couldn't load content. Check your connection and try again.",
                )
            } else {
                HomeUiState(isLoading = false, featured = featured, rows = rows)
            }
        }
    }

    private fun DataResult<List<MediaItem>>.itemsOrEmpty(): List<MediaItem> =
        (this as? DataResult.Success)?.data ?: emptyList()

    private fun firstError(vararg results: DataResult<List<MediaItem>>): String? =
        results.filterIsInstance<DataResult.Error>().firstOrNull()?.message
}
