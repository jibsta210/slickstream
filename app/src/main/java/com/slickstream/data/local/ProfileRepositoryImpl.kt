package com.slickstream.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.slickstream.core.model.Profile
import com.slickstream.core.repository.ProfileRepository
import com.slickstream.data.local.dao.ProfileDao
import com.slickstream.data.local.entity.ProfileEntity
import com.slickstream.data.local.entity.ProfileEntity.Companion.DEFAULT_PROFILE_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Active-profile source of truth. The active profile id is persisted in the shared Preferences
 * [DataStore] (next to the app settings + auth keys); the profiles themselves live in Room.
 * On first run a "Me" default profile is seeded so there is always at least one profile.
 */
@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val dao: ProfileDao,
    private val dataStore: DataStore<Preferences>,
) : ProfileRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Ensure the default profile row exists exactly once.
        scope.launch {
            if (dao.count() == 0) {
                dao.upsert(
                    ProfileEntity(
                        id = DEFAULT_PROFILE_ID,
                        name = "Me",
                        isKids = false,
                        colorIndex = 0,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    override fun observeProfiles(): Flow<List<Profile>> =
        dao.observeAll().map { rows -> rows.map(ProfileEntity::toProfile) }

    override val activeProfileId: StateFlow<String> =
        dataStore.data
            .map { it[KEY_ACTIVE_PROFILE] ?: DEFAULT_PROFILE_ID }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_PROFILE_ID)

    override val activeProfile: StateFlow<Profile?> =
        combine(activeProfileId, observeProfiles()) { id, profiles ->
            profiles.firstOrNull { it.id == id }
                ?: profiles.firstOrNull { it.id == DEFAULT_PROFILE_ID }
                ?: profiles.firstOrNull()
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun currentProfileId(): String =
        dataStore.data.first()[KEY_ACTIVE_PROFILE] ?: DEFAULT_PROFILE_ID

    override suspend fun setActiveProfile(profileId: String) {
        dataStore.edit { it[KEY_ACTIVE_PROFILE] = profileId }
    }

    override suspend fun createProfile(name: String, isKids: Boolean, colorIndex: Int, avatarIndex: Int): Profile {
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = name,
            isKids = isKids,
            colorIndex = colorIndex,
            avatarIndex = avatarIndex,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(ProfileEntity.from(profile))
        return profile
    }

    override suspend fun updateProfile(profile: Profile) {
        dao.upsert(ProfileEntity.from(profile))
    }

    override suspend fun deleteProfile(profileId: String) {
        // The default profile is permanent.
        if (profileId == DEFAULT_PROFILE_ID) return
        dao.deleteById(profileId)
        // If we just deleted the active profile, fall back to the default.
        if (currentProfileId() == profileId) {
            setActiveProfile(DEFAULT_PROFILE_ID)
        }
    }

    override suspend fun allProfiles(): List<Profile> =
        dao.getAll().map(ProfileEntity::toProfile)

    override suspend fun upsertFromSync(profile: Profile) {
        // Upsert the row only — the active profile id (in DataStore) is left untouched, so a profile
        // pulled from another device never hijacks which profile this device is currently using.
        dao.upsert(ProfileEntity.from(profile))
    }

    private companion object {
        val KEY_ACTIVE_PROFILE = stringPreferencesKey("active_profile_id")
    }
}
