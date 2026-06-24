package com.slickstream.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.slickstream.core.model.Profile

/**
 * A viewing profile row. Maps to/from the domain [Profile]. There is always at least the default
 * profile ([DEFAULT_PROFILE_ID]); the active profile id is persisted separately in DataStore.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isKids: Boolean,
    val colorIndex: Int,
    val createdAt: Long,
) {
    fun toProfile(): Profile = Profile(
        id = id,
        name = name,
        isKids = isKids,
        colorIndex = colorIndex,
        createdAt = createdAt,
    )

    companion object {
        /** The seeded profile that always exists; library rows default to this id. */
        const val DEFAULT_PROFILE_ID = "default"

        fun from(profile: Profile): ProfileEntity = ProfileEntity(
            id = profile.id,
            name = profile.name,
            isKids = profile.isKids,
            colorIndex = profile.colorIndex,
            createdAt = profile.createdAt,
        )
    }
}
