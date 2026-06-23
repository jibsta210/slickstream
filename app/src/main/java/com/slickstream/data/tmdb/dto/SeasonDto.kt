package com.slickstream.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** tv/{id}/season/{n} — full season with episode list. */
@Serializable
data class SeasonDetailsDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("episodes") val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("runtime") val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)
