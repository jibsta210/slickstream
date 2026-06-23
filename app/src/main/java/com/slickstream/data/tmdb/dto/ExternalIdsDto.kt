package com.slickstream.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** external_ids append — gives us the imdb_id for tv (and as a fallback for movies). */
@Serializable
data class ExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
)
