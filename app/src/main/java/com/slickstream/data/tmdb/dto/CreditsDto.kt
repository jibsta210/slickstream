package com.slickstream.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** credits append — cast + crew. We only surface cast. */
@Serializable
data class CreditsDto(
    @SerialName("cast") val cast: List<CastDto> = emptyList(),
    @SerialName("crew") val crew: List<CrewDto> = emptyList(),
)

@Serializable
data class CastDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("character") val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("order") val order: Int? = null,
)

@Serializable
data class CrewDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("job") val job: String? = null,
    @SerialName("department") val department: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)
