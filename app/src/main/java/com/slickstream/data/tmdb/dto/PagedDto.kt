package com.slickstream.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Generic TMDB paged envelope. */
@Serializable
data class PagedDto<T>(
    @SerialName("page") val page: Int = 1,
    @SerialName("results") val results: List<T> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_results") val totalResults: Int = 0,
)
