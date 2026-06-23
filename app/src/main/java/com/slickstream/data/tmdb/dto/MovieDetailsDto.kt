package com.slickstream.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** movie/{id}?append_to_response=credits,external_ids */
@Serializable
data class MovieDetailsDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("title") val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("tagline") val tagline: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("runtime") val runtime: Int? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("genres") val genres: List<GenreDto> = emptyList(),
    // Movies expose imdb_id directly on the details payload.
    @SerialName("imdb_id") val imdbId: String? = null,

    // appended
    @SerialName("credits") val credits: CreditsDto? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsDto? = null,
)
