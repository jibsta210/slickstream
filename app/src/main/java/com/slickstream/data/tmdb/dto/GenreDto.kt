package com.slickstream.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GenreDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
)

/** Response of genre/{movie|tv}/list. */
@Serializable
data class GenreListDto(
    @SerialName("genres") val genres: List<GenreDto> = emptyList(),
)
