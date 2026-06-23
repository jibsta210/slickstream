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

    /** Most recently added first. */
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id AND mediaType = :type)")
    suspend fun isFavorite(id: Int, type: MediaType): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id AND mediaType = :type)")
    fun observeIsFavorite(id: Int, type: MediaType): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Delete
    suspend fun delete(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id AND mediaType = :type")
    suspend fun deleteById(id: Int, type: MediaType)
}
