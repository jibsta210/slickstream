package com.slickstream.data.local.entity

import androidx.room.Entity
import com.slickstream.core.model.Download
import com.slickstream.core.model.DownloadStatus
import com.slickstream.core.model.MediaType

/**
 * A queued/in-progress/completed offline download. Composite PK (mediaId, mediaType, season, episode)
 * — season/episode default to -1 for movies (mirrors the watch-history key), so a movie and each
 * episode are distinct rows. Stores enough to render a card + play the local file with no network,
 * plus the chosen source so the download can resume/repin after a restart.
 */
@Entity(tableName = "downloads", primaryKeys = ["mediaId", "mediaType", "season", "episode"])
data class DownloadEntity(
    val mediaId: Int,
    val mediaType: MediaType,
    val season: Int,          // -1 for movies
    val episode: Int,         // -1 for movies
    val title: String,
    val subtitle: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String,
    val quality: String,
    val sizeBytes: Long,
    val infoHash: String?,
    val magnetUri: String?,
    val fileIndex: Int? = null,
    val directUrl: String?,
    val filePath: String?,
    val status: String,       // DownloadStatus.name
    val downloadedBytes: Long,
    val totalBytes: Long,
    val addedAt: Long,
    val profileId: String,
) {
    fun toDownload(): Download = Download(
        mediaId = mediaId,
        mediaType = mediaType,
        season = season.takeIf { it >= 0 },
        episode = episode.takeIf { it >= 0 },
        title = title,
        subtitle = subtitle,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        overview = overview,
        quality = quality,
        sizeBytes = sizeBytes,
        infoHash = infoHash,
        magnetUri = magnetUri,
        fileIndex = fileIndex,
        directUrl = directUrl,
        filePath = filePath,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        addedAt = addedAt,
        profileId = profileId,
    )

    companion object {
        fun from(d: Download): DownloadEntity = DownloadEntity(
            mediaId = d.mediaId,
            mediaType = d.mediaType,
            season = d.season ?: -1,
            episode = d.episode ?: -1,
            title = d.title,
            subtitle = d.subtitle,
            posterUrl = d.posterUrl,
            backdropUrl = d.backdropUrl,
            overview = d.overview,
            quality = d.quality,
            sizeBytes = d.sizeBytes,
            infoHash = d.infoHash,
            magnetUri = d.magnetUri,
            fileIndex = d.fileIndex,
            directUrl = d.directUrl,
            filePath = d.filePath,
            status = d.status.name,
            downloadedBytes = d.downloadedBytes,
            totalBytes = d.totalBytes,
            addedAt = d.addedAt,
            profileId = d.profileId,
        )
    }
}
