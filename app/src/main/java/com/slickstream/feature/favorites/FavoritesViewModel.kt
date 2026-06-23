package com.slickstream.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.FavoriteItem
import com.slickstream.core.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** UI state for the favourites grid. */
data class FavoritesUiState(
    val favorites: List<FavoriteItem> = emptyList(),
    val loading: Boolean = true,
) {
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

    val uiState: StateFlow<FavoritesUiState> =
        libraryRepository.observeFavorites()
            .map { list -> FavoritesUiState(favorites = list, loading = false) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FavoritesUiState(loading = true),
            )
}
