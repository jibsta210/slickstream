package com.slickstream.data.source.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Cinemeta (Stremio's own IMDB-keyed metadata addon, https://v3-cinemeta.strem.io/) — used ONLY to
 * recover an IMDB id + IMDB season numbering when TMDB has none. Measured case: TMDB external_ids
 * carries imdb_id "" for every Netflix "Monster" entry (Dahmer / Menendez / Ed Gein / Lizzie Borden),
 * so "Monster: The Lizzie Borden Story" showed "No IMDB id … cannot resolve sources" on release day,
 * while IMDB/Cinemeta files all four as seasons 1–4 of ONE series, tt13207736.
 *
 * Every field is nullable/defaulted: Cinemeta's rows are community-shaped (some search metas carry
 * only "imdb_id" with no "id"/"type"), and an unknown meta id answers 307 → `{}`.
 */

/** `GET catalog/{series|movie}/top/search=<pct-encoded query>.json` */
@Serializable
data class CinemetaCatalogDto(
    val metas: List<CinemetaMetaDto> = emptyList(),
)

/** `GET meta/{series|movie}/<tt id>.json` — `{}` (meta == null) for an id Cinemeta doesn't know. */
@Serializable
data class CinemetaMetaResponseDto(
    val meta: CinemetaMetaDto? = null,
)

@Serializable
data class CinemetaMetaDto(
    val id: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val type: String? = null,
    val name: String? = null,
    /** "2024" for a movie, "2022–" / "2022-2026" style ranges for a series. */
    val releaseInfo: String? = null,
    /** Arrives as a STRING on some rows and a NUMBER on others — hence JsonElement, so decoding does
     *  not depend on the shared Json staying lenient. Read it through [yearText]. */
    val year: JsonElement? = null,
    val released: String? = null,
    /** Series only: every episode Cinemeta knows, with IMDB season/episode numbers and UTC air stamps. */
    val videos: List<CinemetaVideoDto> = emptyList(),
) {
    /** [year] as text whether it arrived as "2024" or 2024; null for absent/null/non-primitive. */
    val yearText: String? get() = (year as? JsonPrimitive)?.contentOrNull
}

@Serializable
data class CinemetaVideoDto(
    val id: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Some rows number episodes here instead of in [episode]. */
    val number: Int? = null,
    /** ISO UTC timestamp, e.g. "2026-09-17T07:00:00.000Z" — a US-evening premiere can land a day
     *  AFTER TMDB's local "YYYY-MM-DD", which is why season matching allows a small day window. */
    val released: String? = null,
    val firstAired: String? = null,
    val name: String? = null,
)
