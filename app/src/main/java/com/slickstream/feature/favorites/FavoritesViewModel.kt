package com.slickstream.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.FavoriteItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Movies / TV / All segment over the favourites grid. */
enum class FavoritesFilter { ALL, MOVIES, TV }

/** UI state for the favourites grid. */
data class FavoritesUiState(
    val favorites: List<FavoriteItem> = emptyList(),
    val filter: FavoritesFilter = FavoritesFilter.ALL,
    val loading: Boolean = true,
) {
    /** Favourites narrowed to the active [filter]. */
    val filtered: List<FavoriteItem> get() = when (filter) {
        FavoritesFilter.ALL -> favorites
        FavoritesFilter.MOVIES -> favorites.filter { it.media.mediaType == MediaType.MOVIE }
        FavoritesFilter.TV -> favorites.filter { it.media.mediaType == MediaType.TV }
    }

    val isEmpty: Boolean get() = !loading && favorites.isEmpty()
}

/**
 * Streams the user's saved favourites from the local [LibraryRepository].
 * Pure observation — toggling happens from the details screen.
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(FavoritesFilter.ALL)

    fun setFilter(filter: FavoritesFilter) {
        _filter.value = filter
    }

    val uiState: StateFlow<FavoritesUiState> =
        combine(libraryRepository.observeFavorites(), _filter) { list, filter ->
            FavoritesUiState(favorites = list, filter = filter, loading = false)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FavoritesUiState(loading = true),
        )
}
