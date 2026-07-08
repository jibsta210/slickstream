package com.slickstream.core.model

/** Lifecycle of an offline download. */
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

/**
 * An offline download the user requested (a movie or a specific episode). Backed by
 * [com.slickstream.data.local.entity.DownloadEntity]; produced/consumed by the downloads feature.
 * The underlying bytes live in the torrent cache (torrent source) or a downloaded file (direct/RD
 * source), and are PINNED so the LRU cache never evicts them while the download exists.
 */
data class Download(
    val mediaId: Int,
    val mediaType: MediaType,
    val season: Int?,          // null for movies
    val episode: Int?,         // null for movies
    val title: String,
    val subtitle: String,      // "S1 · E3 · <name>" for episodes, "" for movies
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String,
    val quality: String,       // "4K" | "1080p" | "720p" | …
    val sizeBytes: Long,       // expected total from the chosen source (0 if unknown)
    val infoHash: String?,     // torrent source (null for a direct/RD download)
    val magnetUri: String?,
    val directUrl: String?,    // direct/RD source (null for a torrent download)
    val filePath: String?,     // local file once known
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val addedAt: Long,
    val profileId: String,
) {
    /** 0f..1f completion. */
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val isComplete: Boolean get() = status == DownloadStatus.COMPLETED
    /** Stable id for a per-title/episode download (movies use -1/-1 for season/episode). */
    val key: String get() = "${mediaId}_${mediaType.name}_${season ?: -1}_${episode ?: -1}"
}
