package com.slickstream.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.slickstream.core.model.MediaType
import com.slickstream.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {

    /** Most recently watched first — every per-episode row for a profile. */
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId ORDER BY updatedAt DESC")
    fun observeAll(profileId: String): Flow<List<WatchHistoryEntity>>

    /** Every history row across ALL profiles — used by cloud sync (each row carries its profileId). */
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC")
    fun observeAllAcrossProfiles(): Flow<List<WatchHistoryEntity>>

    /** One-shot snapshot of every history row across ALL profiles — used by the initial sync merge. */
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC")
    suspend fun getAllAcrossProfiles(): List<WatchHistoryEntity>

    /**
     * The exact per-episode row. [seasonKey]/[episodeKey] are the non-null key mirrors
     * (-1 == movie / no season-episode).
     */
    @Query(
        "SELECT * FROM watch_history WHERE id = :id AND mediaType = :type " +
            "AND seasonKey = :seasonKey AND episodeKey = :episodeKey AND profileId = :profileId LIMIT 1",
    )
    suspend fun getByEpisode(
        id: Int,
        type: MediaType,
        seasonKey: Int,
        episodeKey: Int,
        profileId: String,
    ): WatchHistoryEntity?

    /** Most-recently-touched row for a title — used to resume "this show" from the rail. */
    @Query(
        "SELECT * FROM watch_history WHERE id = :id AND mediaType = :type AND profileId = :profileId " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun getLatestForMedia(id: Int, type: MediaType, profileId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    /** Atomic last-write-wins merge for cloud updates. The coordinator's pre-read avoids most no-op
     *  calls, while this transaction closes the race between two devices/listener callbacks. */
    @Transaction
    suspend fun upsertIfNewer(entity: WatchHistoryEntity) {
        val current = getByEpisode(
            entity.id,
            entity.mediaType,
            entity.seasonKey,
            entity.episodeKey,
            entity.profileId,
        )
        if (current == null || entity.updatedAt > current.updatedAt) upsert(entity)
    }

    /** Delete a single episode row (or the lone movie row when seasonKey/episodeKey == -1). */
    @Query(
        "DELETE FROM watch_history WHERE id = :id AND mediaType = :type " +
            "AND seasonKey = :seasonKey AND episodeKey = :episodeKey AND profileId = :profileId",
    )
    suspend fun deleteEpisode(
        id: Int,
        type: MediaType,
        seasonKey: Int,
        episodeKey: Int,
        profileId: String,
    )

    /** Delete every row for a title (all episodes) — used by "remove from history". */
    @Query("DELETE FROM watch_history WHERE id = :id AND mediaType = :type AND profileId = :profileId")
    suspend fun deleteAllForMedia(id: Int, type: MediaType, profileId: String)

    /** Clear only the active profile's history (never the whole table). */
    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun clear(profileId: String)

    /** Move every history row from one profile id to another (profile reconcile). OR REPLACE so a row
     *  already present under the target id collapses instead of a PK-conflict crash. */
    @Query("UPDATE OR REPLACE watch_history SET profileId = :toId WHERE profileId = :fromId")
    suspend fun reassignProfile(fromId: String, toId: String)
}
