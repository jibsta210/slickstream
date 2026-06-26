package com.slickstream.data.local.entity

import androidx.room.ColumnInfo
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
    // Default matches MIGRATION_3_4's ADD COLUMN ... DEFAULT 0 so Room's schema validation is exact.
    @ColumnInfo(defaultValue = "0") val avatarIndex: Int = 0,
    val createdAt: Long,
    // Default matches MIGRATION_4_5's ADD COLUMN ... DEFAULT 0; existing rows are backfilled to createdAt.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
) {
    fun toProfile(): Profile = Profile(
        id = id,
        name = name,
        isKids = isKids,
        colorIndex = colorIndex,
        avatarIndex = avatarIndex,
        createdAt = createdAt,
        updatedAt = if (updatedAt > 0L) updatedAt else createdAt,
    )

    companion object {
        /** The seeded profile that always exists; library rows default to this id. */
        const val DEFAULT_PROFILE_ID = "default"

        fun from(profile: Profile): ProfileEntity = ProfileEntity(
            id = profile.id,
            name = profile.name,
            isKids = profile.isKids,
            colorIndex = profile.colorIndex,
            avatarIndex = profile.avatarIndex,
            createdAt = profile.createdAt,
            updatedAt = if (profile.updatedAt > 0L) profile.updatedAt else profile.createdAt,
        )
    }
}
