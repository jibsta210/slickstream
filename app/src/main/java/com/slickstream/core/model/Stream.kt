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
    /** Episode identity used to recover the right file from a season pack when an addon omits fileIdx. */
    val expectedSeason: Int? = null,
    val expectedEpisode: Int? = null,
    /** True when this source is a season/multi-episode PACK rather than a single-file release. Packs
     *  stream slower to start (large pieces, the wanted episode sits mid-file behind a shared boundary
     *  piece), so the picker prefers a single-file episode when one exists. */
    val isPack: Boolean = false,
    /** False when the torrent text signals a non-English language (so we can default to English). */
    val englishLikely: Boolean = true,
    /** False when the release names a codec/container ExoPlayer can't decode (XviD/DivX/AVI/WMV…). */
    val playable: Boolean = true,
    /** True when the release name marks it a CAM / TS / TELESYNC (a filmed-in-cinema rip — terrible
     *  quality, common for new releases). Surfaced to the user and sorted/picked below real encodes. */
    val isCam: Boolean = false,
    /** True when the release names a container whose index sits at the FRONT (mkv/webm) rather than at
     *  EOF (mp4/m4v/mov). Front-index files start on the head alone — no EOF "moov" fetch, which on a
     *  big-piece torrent is the single largest component of time-to-first-frame. Soft-preferred by the
     *  picker, never required: an unknown/absent container tag is treated as "not known to be fast". */
    val frontIndexContainer: Boolean = false,
    /** A DIRECT http/hls URL for a non-torrent source (free streaming addon). When set, the player
     *  bypasses the torrent engine and streams this URL — instant, no P2P ("file-server-first"). */
    val directUrl: String? = null,
    /** HTTP request headers (Referer/Origin/User-Agent) the direct host requires — without them many
     *  CDNs return 403 and the stream starts but never plays. Carried to the ExoPlayer/libVLC data
     *  source for direct sources; empty for torrents. */
    val requestHeaders: Map<String, String> = emptyMap(),
    /** True when [directUrl] is a local `file://` path from a completed offline download. The player
     *  hands it straight to ExoPlayer (no HTTP HEAD validation, no failover, no network) — the whole
     *  point of "downloaded for the plane". Still [isDirect] (directUrl != null), so all the
     *  direct-source guards (never-downshift, no swarm UI) apply for free. */
    val isOffline: Boolean = false,
) {
    /** True for a direct HTTP/HLS source (no torrent). */
    val isDirect: Boolean get() = directUrl != null
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
            // A CAM is worse than any real encode regardless of its claimed quality/seeders — sink it
            // below everything else so the ranked list (and the auto-pick) only reach it as a last resort.
            val camPenalty = if (isCam) 1_000_000 else 0
            return q + (seeders ?: 0) - camPenalty
        }
}

enum class StreamState { IDLE, METADATA, BUFFERING, READY, PAUSED, ERROR, COMPLETED }

/**
 * What the startup gate is still waiting for, in the gate's OWN words. The loading overlay and the
 * chunk bar both render from this, so neither can claim something the engine isn't measuring — the
 * "solid teal head, yet 'low buffer'" contract violation. See
 * [com.slickstream.data.torrent.StartGate].
 */
enum class StartupBlocker {
    /** No contiguous container header at the file start yet (still finding peers / first piece). */
    CONTAINER_HEADER,
    /** Header is in, but the bytes UNDER the resume position are not (resume anchoring). */
    PLAYHEAD,
    /** Head is in; a non-faststart MP4 still needs its EOF `moov` index before the first frame. */
    MOOV_INDEX,
    /** Nothing left to wait for — the gate is open. */
    NONE,
}

/** Live status emitted by [com.slickstream.core.repository.TorrentStreamer.start]. */
data class StreamStatus(
    val infoHash: String,
    val state: StreamState,
    val progress: Float,            // 0f..1f of the selected file
    /** TOTAL WIRE rate. Includes protocol overhead, hash-failed re-fetches and redundant duplicate
     *  blocks, so it is NOT "bytes downloaded per second" — it is what the link moved. Reserved for
     *  liveness and failover decisions; never print it under a "downloading at" label. */
    val downloadRateBytes: Int,
    /** Bytes per second that actually became file — the number to DISPLAY. Falls back to the wire rate
     *  only while there is no payload at all (metadata fetch). See [com.slickstream.data.torrent.TransferAccounting]. */
    val payloadRateBytes: Int = 0,
    /** Cumulative payload received, and cumulative bytes received and discarded (hash-failed +
     *  redundant). Their ratio is the gap between the speed and the progress, made visible. */
    val payloadBytes: Long = 0L,
    val wastedBytes: Long = 0L,
    val uploadRateBytes: Int = 0,
    val seeders: Int,
    val peers: Int,
    val downloadedBytes: Long,
    /** Bytes available contiguously from the selected file's start. Unlike [downloadedBytes], this
     *  excludes scattered/tail pieces and is the authoritative startup-buffer progress signal. */
    val contiguousHeadBytes: Long = 0L,
    val totalBytes: Long,
    val streamUrl: String?,         // local http URL once playable, else null
    val errorMessage: String? = null,
    /** True after the contiguous head is ready while an MP4/MOV still needs its EOF moov pieces.
     *  The player defers its head-stall watchdog to the streamer's dedicated bounded tail wait.
     *  Derived from [startupBlocker] so there is only ever one source of truth. */
    val awaitingStartupTail: Boolean = false,
    /** The single requirement the readiness gate is still missing, or [StartupBlocker.NONE] once open. */
    val startupBlocker: StartupBlocker = StartupBlocker.NONE,
    /** Total bytes the readiness gate asked for (header + playhead + EOF index, as applicable). The
     *  DENOMINATOR of the startup progress bar — using it is what stops the bar reading 100% while the
     *  gate is still waiting on the moov. 0 once playing / not applicable. */
    val startupRequiredBytes: Long = 0L,
    /** Bytes of [startupRequiredBytes] still missing. Drives both the progress bar and [etaSeconds]. */
    val startupRemainingBytes: Long = 0L,
    /** 0f..1f toward playable, straight from the gate's own [com.slickstream.data.torrent.StartDecision]
     *  — including its "never 1.0 while a requirement is outstanding" clamp. Carried rather than
     *  recomputed in the UI: the recomputation dropped the clamp and put "100% · Almost ready…" over a
     *  gate that was still shut. */
    val startupFillFraction: Float = 0f,
    /** Best-effort seconds until first frame (head + mp4 moov tail ÷ rate), or null when not estimable
     *  (e.g. still discovering peers / no download rate yet). Computed by the streamer against the same
     *  readiness gate that flips to READY, so the countdown matches when playback actually starts. */
    val etaSeconds: Int? = null,
    /** libtorrent is hash-verifying already-on-disk data (cached reopen / resume) — rate + seeders read
     *  0 but it is NOT a stall. Lets the chunk bar show "checking cached data" instead of a false "stalled". */
    val isChecking: Boolean = false,
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
