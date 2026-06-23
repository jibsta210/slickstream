package com.slickstream.data.local.entity

import androidx.room.Entity
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.WatchHistoryItem

/**
 * A "Continue watching" entry. Stores enough [MediaItem] fields to rebuild the card plus
 * the resume point ([PlaybackProgress]). Composite primary key = (id, mediaType) so a
 * title collapses to a single most-recent row in the rail.
 */
@Entity(tableName = "watch_history", primaryKeys = ["id", "mediaType"])
data class WatchHistoryEntity(
    val id: Int,
    val mediaType: MediaType,
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val voteAverage: Double,
    val releaseDate: String?,
    val imdbId: String?,
    val addedAt: Long,
    // --- resume point ---
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val infoHash: String?,
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = id,
        mediaType = mediaType,
        title = title,
        overview = overview,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        voteAverage = voteAverage,
        releaseDate = releaseDate,
        imdbId = imdbId,
    )

    fun toPlaybackProgress(): PlaybackProgress = PlaybackProgress(
        mediaId = id,
        mediaType = mediaType,
        season = season,
        episode = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
        infoHash = infoHash,
    )

    fun toWatchHistoryItem(): WatchHistoryItem = WatchHistoryItem(
        media = toMediaItem(),
        progress = toPlaybackProgress(),
    )

    companion object {
        fun from(item: MediaItem, progress: PlaybackProgress, addedAt: Long): WatchHistoryEntity =
            WatchHistoryEntity(
                id = item.id,
                mediaType = item.mediaType,
                title = item.title,
                overview = item.overview,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                voteAverage = item.voteAverage,
                releaseDate = item.releaseDate,
                imdbId = item.imdbId,
                addedAt = addedAt,
                season = progress.season,
                episode = progress.episode,
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                updatedAt = progress.updatedAt,
                infoHash = progress.infoHash,
            )
    }
}
