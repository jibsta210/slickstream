package com.slickstream.data.subtitle

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Stremio-compatible subtitle addon (keyless, like the torrent indexer).
 * Endpoint: subtitles/{type}/{id}.json — id = IMDB id (movie) or "tt..:season:episode" (series).
 */
interface SubtitleApi {
    @GET("subtitles/{type}/{id}.json")
    suspend fun getSubtitles(
        @Path("type") type: String,
        @Path("id") id: String,
    ): SubtitleResponseDto
}

@Serializable
data class SubtitleResponseDto(
    val subtitles: List<SubtitleItemDto> = emptyList(),
)

@Serializable
data class SubtitleItemDto(
    val id: String? = null,
    val url: String? = null,
    val lang: String? = null,
)
