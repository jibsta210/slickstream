package com.slickstream.data.source.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the Stremio/Torrentio-compatible stream endpoint:
 *   GET {base}stream/{type}/{id}.json
 *
 * Only the fields SlickStream needs are modelled; the shared [com.slickstream.di.CoreNetworkModule]
 * Json instance is configured with ignoreUnknownKeys = true, so extra fields are tolerated.
 */
@Serializable
data class StreamResponseDto(
    val streams: List<StreamDto> = emptyList(),
)

@Serializable
data class StreamDto(
    /** Short provider/quality label, e.g. "Torrentio\n1080p". */
    val name: String? = null,
    /** Rich multi-line description carrying filename, seeders ("👤 123") and size ("💾 2.1 GB"). */
    val title: String? = null,
    /** Alternate field some indexers use instead of [title]. */
    val description: String? = null,
    val infoHash: String? = null,
    /** Index of the desired file inside a multi-file torrent. */
    val fileIdx: Int? = null,
    /** A DIRECT http/hls stream URL (Stremio standard) — present INSTEAD of [infoHash] for non-torrent
     *  addons (free direct-streaming). When set, the app plays it straight, no P2P. */
    val url: String? = null,
    @SerialName("behaviorHints")
    val behaviorHints: BehaviorHintsDto? = null,
)

@Serializable
data class BehaviorHintsDto(
    /** Stremio "binge group" — often encodes provider + quality. */
    val bingeGroup: String? = null,
    val filename: String? = null,
    /** File size in bytes, when the indexer provides it directly. */
    val videoSize: Long? = null,
    /** HTTP headers the player MUST send when fetching a DIRECT [StreamDto.url] — many streaming hosts
     *  reject requests without the right Referer/Origin/User-Agent (403/empty), which is exactly why a
     *  found direct stream starts the player but never plays. Threaded to ExoPlayer/libVLC for direct
     *  sources; ignored for torrents. */
    val proxyHeaders: ProxyHeadersDto? = null,
)

@Serializable
data class ProxyHeadersDto(
    /** Outgoing headers (Referer/Origin/User-Agent…) the player must send. ("response" is unused.) */
    val request: Map<String, String>? = null,
)
