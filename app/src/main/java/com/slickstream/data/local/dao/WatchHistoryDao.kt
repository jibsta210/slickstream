package com.slickstream.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.slickstream.core.model.MediaType
import com.slickstream.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {

    /** Most recently watched first — drives the "Continue watching" rail. */
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE id = :id AND mediaType = :type LIMIT 1")
    suspend fun getByMedia(id: Int, type: MediaType): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE id = :id AND mediaType = :type")
    suspend fun deleteById(id: Int, type: MediaType)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}
