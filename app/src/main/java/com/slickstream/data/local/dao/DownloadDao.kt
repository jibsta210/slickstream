package com.slickstream.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.slickstream.core.model.MediaType
import com.slickstream.data.local.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    /** All downloads (any profile), newest first — the Downloads screen. */
    @Query("SELECT * FROM downloads ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    /** One-shot snapshot — used at launch to re-pin/resume in-flight downloads. */
    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads WHERE mediaId = :mediaId AND mediaType = :type " +
            "AND season = :season AND episode = :episode LIMIT 1",
    )
    suspend fun get(mediaId: Int, type: MediaType, season: Int, episode: Int): DownloadEntity?

    /** Live status of one item (for the details-screen download button). */
    @Query(
        "SELECT * FROM downloads WHERE mediaId = :mediaId AND mediaType = :type " +
            "AND season = :season AND episode = :episode LIMIT 1",
    )
    fun observe(mediaId: Int, type: MediaType, season: Int, episode: Int): Flow<DownloadEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query(
        "UPDATE downloads SET status = :status, downloadedBytes = :downloaded, totalBytes = :total, " +
            "filePath = COALESCE(:filePath, filePath) WHERE mediaId = :mediaId AND mediaType = :type " +
            "AND season = :season AND episode = :episode",
    )
    suspend fun updateProgress(
        mediaId: Int, type: MediaType, season: Int, episode: Int,
        status: String, downloaded: Long, total: Long, filePath: String?,
    )

    @Delete
    suspend fun delete(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE mediaId = :mediaId AND mediaType = :type AND season = :season AND episode = :episode")
    suspend fun deleteByKey(mediaId: Int, type: MediaType, season: Int, episode: Int)
}
