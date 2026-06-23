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
)
