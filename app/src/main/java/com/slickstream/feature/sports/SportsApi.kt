package com.slickstream.feature.sports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Live-sports schedule feed (keyless). ppv.st publishes its whole schedule as one document:
 *   GET https://api.ppv.st/api/streams -> { timestamp, streams: [ {category, id, streams: [...]}, ... ] }
 * Each event carries an `iframe` embed PAGE (plus `substreams`, e.g. an SD copy) that the WebView
 * resolver turns into the real HLS playlist.
 *
 * History: this used to be streamed.pk's REST API. streamed.pk shut down (its site now reads "We are
 * no longer operating Streamed" and every /api path 301s to that page), which left the Sports tab
 * completely empty; ppv.st was the first alternative it recommended and the only one with a feed.
 */
interface SportsApi {
    @GET
    suspend fun schedule(@Url url: String): PpvScheduleDto
}

@Serializable
data class PpvScheduleDto(
    val success: Boolean = false,
    /** Server clock, epoch SECONDS. */
    val timestamp: Long = 0L,
    val streams: List<PpvCategoryDto> = emptyList(),
)

@Serializable
data class PpvCategoryDto(
    val id: Long = 0L,
    val category: String = "",
    val streams: List<PpvStreamDto> = emptyList(),
)

@Serializable
data class PpvStreamDto(
    val id: Long = 0L,
    val name: String = "",
    val tag: String? = null,
    @SerialName("source_tag") val sourceTag: String? = null,
    val poster: String? = null,
    /** Epoch SECONDS; 0 for 24/7 channels. */
    @SerialName("starts_at") val startsAt: Long = 0L,
    @SerialName("ends_at") val endsAt: Long = 0L,
    /** 0/1 on the wire today; JsonElement so a true/false switch can't break decoding. */
    @SerialName("always_live") val alwaysLiveRaw: JsonElement? = null,
    @SerialName("category_name") val categoryName: String? = null,
    val iframe: String? = null,
    val substreams: List<PpvSubstreamDto> = emptyList(),
) {
    val alwaysLive: Boolean
        get() = (alwaysLiveRaw as? JsonPrimitive)?.let { p ->
            p.booleanOrNull ?: p.longOrNull?.let { it != 0L }
        } ?: false
}

@Serializable
data class PpvSubstreamDto(
    val id: Long = 0L,
    val name: String? = null,
    val tag: String? = null,
    @SerialName("source_tag") val sourceTag: String? = null,
    val iframe: String? = null,
)
