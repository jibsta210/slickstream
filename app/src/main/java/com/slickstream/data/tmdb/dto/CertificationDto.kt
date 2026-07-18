package com.slickstream.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** /movie/{id}/release_dates — per-country certification entries (kids-profile filtering). */
@Serializable
data class ReleaseDatesDto(
    @SerialName("results") val results: List<CountryReleaseDto> = emptyList(),
)

@Serializable
data class CountryReleaseDto(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val releaseDates: List<ReleaseEntryDto> = emptyList(),
)

@Serializable
data class ReleaseEntryDto(
    @SerialName("certification") val certification: String? = null,
)

/** /tv/{id}/content_ratings — per-country TV ratings (TV-Y … TV-MA). */
@Serializable
data class ContentRatingsDto(
    @SerialName("results") val results: List<ContentRatingDto> = emptyList(),
)

@Serializable
data class ContentRatingDto(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("rating") val rating: String? = null,
)
