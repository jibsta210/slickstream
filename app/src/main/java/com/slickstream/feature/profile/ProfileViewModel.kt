package com.slickstream.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the profile screen's local-library actions. Auth/user state is owned by
 * [com.slickstream.feature.auth.AuthViewModel]; this only handles the "clear watch history" action.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    fun clearWatchHistory() {
        viewModelScope.launch {
            libraryRepository.clearHistory()
        }
    }
}
