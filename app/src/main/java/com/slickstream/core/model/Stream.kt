package com.slickstream.core.model

/**
 * A resolved playable torrent source for a given title/episode. Produced by
 * [com.slickstream.core.repository.SourceRepository], consumed by the player.
 */
data class StreamSource(
    val title: String,           // human label e.g. "Torrentio 1080p WEB-DL"
    val magnetUri: String,
    val infoHash: String,
    val quality: String,         // "4K" | "1080p" | "720p" | "480p" | "SD"
    val sizeBytes: Long?,
    val seeders: Int?,
    val provider: String,        // indexer / tracker label
    val fileIndex: Int? = null,  // which file inside a multi-file torrent, if known
    /** False when the torrent text signals a non-English language (so we can default to English). */
    val englishLikely: Boolean = true,
) {
    /** Rough sort key — higher is better. */
    val rank: Int
        get() {
            val q = when (quality.uppercase()) {
                "4K", "2160P" -> 4000
                "1080P" -> 3000
                "720P" -> 2000
                "480P" -> 1000
                else -> 0
            }
            return q + (seeders ?: 0)
        }
}

enum class StreamState { IDLE, METADATA, BUFFERING, READY, PAUSED, ERROR, COMPLETED }

/** Live status emitted by [com.slickstream.core.repository.TorrentStreamer.start]. */
data class StreamStatus(
    val infoHash: String,
    val state: StreamState,
    val progress: Float,            // 0f..1f of the selected file
    val downloadRateBytes: Int,
    val uploadRateBytes: Int = 0,
    val seeders: Int,
    val peers: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val streamUrl: String?,         // local http URL once playable, else null
    val errorMessage: String? = null,
)

/** Persisted resume point for a movie or a specific episode. */
data class PlaybackProgress(
    val mediaId: Int,
    val mediaType: MediaType,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val infoHash: String? = null,
) {
    val percent: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val isFinished: Boolean get() = percent >= 0.92f
}
