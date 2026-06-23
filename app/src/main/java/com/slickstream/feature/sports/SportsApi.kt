package com.slickstream.feature.sports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * streamed.pk-compatible live-sports REST API (keyless). Documented endpoints:
 *  - GET api/sports                      -> categories (sport/league)
 *  - GET api/matches/{sport}             -> events for one category
 *  - GET api/matches/live                -> currently-live events across all sports
 *  - GET api/stream/{source}/{id}        -> playable feeds for one event source
 *
 * The base URL is supplied by [com.slickstream.feature.sports.di.SportsModule] (BuildConfig
 * SPORTS_BASE_URL) so the source can be swapped or disabled without touching code.
 */
interface SportsApi {

    @GET("api/sports")
    suspend fun sports(): List<SportDto>

    @GET("api/matches/{sport}")
    suspend fun matches(@Path("sport") sport: String): List<MatchDto>

    @GET("api/matches/live")
    suspend fun liveMatches(): List<MatchDto>

    @GET("api/stream/{source}/{id}")
    suspend fun streams(
        @Path("source") source: String,
        @Path("id") id: String,
    ): List<StreamDto>
}

@Serializable
data class SportDto(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class MatchDto(
    val id: String = "",
    val title: String = "",
    val category: String = "",
    /** Epoch millis of kickoff (0 when unknown). */
    val date: Long = 0L,
    val poster: String? = null,
    val popular: Boolean = false,
    val sources: List<SourceDto> = emptyList(),
)

@Serializable
data class SourceDto(
    val source: String = "",
    val id: String = "",
)

@Serializable
data class StreamDto(
    val id: String = "",
    @SerialName("streamNo") val streamNo: Int = 0,
    val language: String? = null,
    val hd: Boolean = false,
    val source: String = "",
    val embedUrl: String = "",
)
