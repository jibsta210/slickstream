package com.slickstream.core.model

/** Whether a catalog entry is a movie or a TV series. */
enum class MediaType {
    MOVIE,
    TV;

    companion object {
        fun fromTmdb(value: String?): MediaType = if (value == "tv") TV else MOVIE
        fun fromName(value: String?): MediaType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MOVIE
    }
}

/**
 * A single browsable catalog item (card). All image paths are already resolved to
 * full URLs by the data layer — UI never has to build TMDB image URLs itself.
 *
 * @Immutable: the `genreIds: List<Int>` field would otherwise mark this class unstable to the
 * Compose compiler, forcing every visible poster card to recompose on any parent state change
 * (e.g. focus). The contents are effectively immutable, so this lets the poster rows skip.
 */
@androidx.compose.runtime.Immutable
data class MediaItem(
    val id: Int,                 // TMDB id
    val mediaType: MediaType,
    val title: String,
    val overview: String,
    val posterUrl: String?,      // full image URL
    val backdropUrl: String?,    // full image URL
    val voteAverage: Double,     // 0..10
    val releaseDate: String?,    // "YYYY-MM-DD"
    val genreIds: List<Int> = emptyList(),
    val imdbId: String? = null,  // populated on details fetch ("tt1234567")
    val originalLanguage: String? = null,  // ISO-639-1 ("en","hi","ta"…), for the region filter
) {
    val year: String? get() = releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4)
}

data class Genre(val id: Int, val name: String)

data class CastMember(
    val id: Int,
    val name: String,
    val character: String,
    val profileUrl: String?,
)

data class Season(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val posterUrl: String?,
    val overview: String = "",
)

data class Episode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String,
    val stillUrl: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
)

/** ISO air dates ("YYYY-MM-DD") compare lexicographically, so a date string <= today means "aired".
 *  An episode with NO date (or a future date) has NOT aired — there's nothing to stream yet. */
fun Episode.hasAired(today: String = isoToday()): Boolean = airDate?.let { it <= today } ?: false

/** Downloadable = aired OR unknown air date (some shows omit dates but the release exists). MUST stay
 *  in sync with DownloadManager.downloadSeason's enqueue filter — the download buttons/aggregate
 *  counters gate on exactly the set the backend will enqueue, or the UI hides/undercounts rows the
 *  season download actually creates. */
fun Episode.isDownloadable(): Boolean = airDate == null || hasAired()

/** Today as "YYYY-MM-DD" in local time — no java.time needed (works on every API level). */
fun isoToday(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

/** Full detail payload for a details screen. */
data class MediaDetails(
    val item: MediaItem,
    val tagline: String?,
    val runtimeMinutes: Int?,        // movies
    val genres: List<Genre>,
    val cast: List<CastMember>,
    val seasons: List<Season>,       // empty for movies
    val numberOfSeasons: Int? = null,
    val status: String? = null,
    val imdbId: String?,             // required to resolve torrent sources
    /** TV only: the most-recently-aired and next-scheduled episodes (TMDB inline). Lets the home
     *  "New Episodes" rail spot a fresh drop without enumerating every season. Null for movies. */
    val lastEpisodeToAir: Episode? = null,
    val nextEpisodeToAir: Episode? = null,
)
