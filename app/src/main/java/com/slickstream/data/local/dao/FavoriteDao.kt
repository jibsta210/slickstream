package com.slickstream.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.slickstream.core.model.MediaType
import com.slickstream.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    /** Most recently added first, scoped to a profile. */
    @Query("SELECT * FROM favorites WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun observeAll(profileId: String): Flow<List<FavoriteEntity>>

    /** Every favourite across ALL profiles — used by cloud sync (each row carries its profileId). */
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAllAcrossProfiles(): Flow<List<FavoriteEntity>>

    /** One-shot snapshot of every favourite across ALL profiles — used by the initial sync merge. */
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun getAllAcrossProfiles(): List<FavoriteEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites " +
            "WHERE id = :id AND mediaType = :type AND profileId = :profileId)",
    )
    suspend fun isFavorite(id: Int, type: MediaType, profileId: String): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites " +
            "WHERE id = :id AND mediaType = :type AND profileId = :profileId)",
    )
    fun observeIsFavorite(id: Int, type: MediaType, profileId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Delete
    suspend fun delete(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id AND mediaType = :type AND profileId = :profileId")
    suspend fun deleteById(id: Int, type: MediaType, profileId: String)
}
