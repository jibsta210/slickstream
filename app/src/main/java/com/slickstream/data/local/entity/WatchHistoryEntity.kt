package com.slickstream.data.local.entity

import androidx.room.Entity
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.WatchHistoryItem

/**
 * A per-episode watch-history entry. Stores enough [MediaItem] fields to rebuild the card plus
 * the resume point ([PlaybackProgress]). Composite primary key = (id, mediaType, seasonKey,
 * episodeKey) so every episode of a series gets its own row (and a movie gets exactly one).
 *
 * Room primary-key columns cannot be null, so the nullable [season]/[episode] are mirrored into
 * non-null [seasonKey]/[episodeKey] sentinels (-1 == "none", i.e. a movie) used only for keying.
 */
@Entity(tableName = "watch_history", primaryKeys = ["id", "mediaType", "seasonKey", "episodeKey"])
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
    /** Non-null mirror of [season] for the composite key (-1 == movie / no season). */
    val seasonKey: Int,
    /** Non-null mirror of [episode] for the composite key (-1 == movie / no episode). */
    val episodeKey: Int,
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
        /** Composite-key sentinel for "no season / no episode" (movies). */
        const val NO_KEY = -1

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
                seasonKey = progress.season ?: NO_KEY,
                episodeKey = progress.episode ?: NO_KEY,
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                updatedAt = progress.updatedAt,
                infoHash = progress.infoHash,
            )
    }
}
