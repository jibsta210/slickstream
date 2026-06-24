package com.slickstream.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.Profile
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.ProfileRepository
import com.slickstream.ui.components.profileAvatarColorCount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the multi-profile experience (picker + manage) AND the legacy profile tab's
 * "clear watch history" action.
 *
 * The named-profile CRUD + active-profile switching is delegated to [ProfileRepository]
 * (favourites/watch-history are already scoped to the active profile inside the library layer,
 * so [select] alone re-queries the right library). [clearWatchHistory] is kept for the existing
 * phone/TV profile screens.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    /** All profiles (always contains at least the default "Me"). */
    val profiles: StateFlow<List<Profile>> =
        profileRepository.observeProfiles()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The currently active profile (drives the avatar + kids Home rows). */
    val activeProfile: StateFlow<Profile?> = profileRepository.activeProfile

    /** Make [id] the active profile. Library re-queries automatically. */
    fun select(id: String) {
        viewModelScope.launch { profileRepository.setActiveProfile(id) }
    }

    /** Create a profile, then immediately switch to it. */
    fun create(name: String, isKids: Boolean, colorIndex: Int) {
        viewModelScope.launch {
            val created = profileRepository.createProfile(
                name = name.trim(),
                isKids = isKids,
                colorIndex = colorIndex,
            )
            profileRepository.setActiveProfile(created.id)
        }
    }

    /** Rename / toggle kids / recolour an existing profile. */
    fun update(profile: Profile) {
        viewModelScope.launch { profileRepository.updateProfile(profile) }
    }

    /** Delete a profile. The repository falls back to the default if the active one is removed. */
    fun delete(id: String) {
        viewModelScope.launch { profileRepository.deleteProfile(id) }
    }

    /**
     * The next free avatar colour index — cycles the palette by how many profiles already exist,
     * so a fresh profile picks a visually distinct tint.
     */
    fun nextColorIndex(): Int = profiles.value.size % profileAvatarColorCount

    fun clearWatchHistory() {
        viewModelScope.launch {
            libraryRepository.clearHistory()
        }
    }
}
