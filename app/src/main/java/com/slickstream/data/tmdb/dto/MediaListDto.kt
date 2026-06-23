package com.slickstream.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single list/card item as returned by trending, popular, top_rated, discover,
 * now_playing, on_the_air, similar and search/multi.
 *
 * TMDB uses different fields for movies vs tv:
 *   - movie: [title], [releaseDate]
 *   - tv:    [name],  [firstAirDate]
 * For search/multi a [mediaType] discriminator ("movie" | "tv" | "person") is present;
 * other endpoints omit it (the caller knows the type from the request).
 */
@Serializable
data class MediaListItemDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,

    // movie
    @SerialName("title") val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,

    // tv
    @SerialName("name") val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,

    @SerialName("overview") val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("genre_ids") val genreIds: List<Int>? = null,
)
