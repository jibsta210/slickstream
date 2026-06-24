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

    /** Most recently watched first — every per-episode row. */
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WatchHistoryEntity>>

    /**
     * The exact per-episode row. [seasonKey]/[episodeKey] are the non-null key mirrors
     * (-1 == movie / no season-episode).
     */
    @Query(
        "SELECT * FROM watch_history WHERE id = :id AND mediaType = :type " +
            "AND seasonKey = :seasonKey AND episodeKey = :episodeKey LIMIT 1",
    )
    suspend fun getByEpisode(id: Int, type: MediaType, seasonKey: Int, episodeKey: Int): WatchHistoryEntity?

    /** Most-recently-touched row for a title — used to resume "this show" from the rail. */
    @Query(
        "SELECT * FROM watch_history WHERE id = :id AND mediaType = :type " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun getLatestForMedia(id: Int, type: MediaType): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    /** Delete a single episode row (or the lone movie row when seasonKey/episodeKey == -1). */
    @Query(
        "DELETE FROM watch_history WHERE id = :id AND mediaType = :type " +
            "AND seasonKey = :seasonKey AND episodeKey = :episodeKey",
    )
    suspend fun deleteEpisode(id: Int, type: MediaType, seasonKey: Int, episodeKey: Int)

    /** Delete every row for a title (all episodes) — used by "remove from history". */
    @Query("DELETE FROM watch_history WHERE id = :id AND mediaType = :type")
    suspend fun deleteAllForMedia(id: Int, type: MediaType)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}
