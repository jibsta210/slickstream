package com.slickstream.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** tv/{id}?append_to_response=credits,external_ids */
@Serializable
data class TvDetailsDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("tagline") val tagline: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("genres") val genres: List<GenreDto> = emptyList(),
    @SerialName("seasons") val seasons: List<SeasonSummaryDto> = emptyList(),
    @SerialName("last_episode_to_air") val lastEpisodeToAir: EpisodeToAirDto? = null,
    @SerialName("next_episode_to_air") val nextEpisodeToAir: EpisodeToAirDto? = null,

    // appended
    @SerialName("credits") val credits: CreditsDto? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsDto? = null,
)

/** The series' most-recently-aired / next-scheduled episode, returned inline on tv details — lets us
 *  spot a fresh episode without enumerating every season. */
@Serializable
data class EpisodeToAirDto(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("runtime") val runtime: Int? = null,
)

/** A season entry embedded in tv details. */
@Serializable
data class SeasonSummaryDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
)
