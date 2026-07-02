package com.slickstream.data.tmdb

import com.slickstream.core.common.Img
import com.slickstream.core.model.CastMember
import com.slickstream.core.model.Episode
import com.slickstream.core.model.Genre
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.Season
import com.slickstream.data.tmdb.dto.CastDto
import com.slickstream.data.tmdb.dto.EpisodeDto
import com.slickstream.data.tmdb.dto.EpisodeToAirDto
import com.slickstream.data.tmdb.dto.GenreDto
import com.slickstream.data.tmdb.dto.MediaListItemDto
import com.slickstream.data.tmdb.dto.MovieDetailsDto
import com.slickstream.data.tmdb.dto.SeasonSummaryDto
import com.slickstream.data.tmdb.dto.TvDetailsDto

/**
 * DTO -> domain mappers. Every TMDB image path is resolved to a full URL via [Img.url]
 * so the UI never builds image URLs itself. The maximum cast list surfaced is capped.
 */
private const val MAX_CAST = 20

/** Maps the API's "movie"/"tv" path segment for a [MediaType]. */
internal fun MediaType.path(): String = when (this) {
    MediaType.MOVIE -> "movie"
    MediaType.TV -> "tv"
}

internal fun GenreDto.toDomain(): Genre = Genre(id = id, name = name)

internal fun CastDto.toDomain(): CastMember = CastMember(
    id = id,
    name = name,
    character = character.orEmpty(),
    profileUrl = Img.url(profilePath, Img.PROFILE),
)

/**
 * Maps a list/card item. [forcedType] is supplied for endpoints that don't echo a
 * media_type (popular, top_rated, discover, similar, …); search/multi carries its own.
 */
internal fun MediaListItemDto.toDomain(forcedType: MediaType?): MediaItem {
    val type = forcedType ?: MediaType.fromTmdb(mediaType)
    val resolvedTitle = when (type) {
        MediaType.MOVIE -> title ?: name
        MediaType.TV -> name ?: title
    }
    val date = when (type) {
        MediaType.MOVIE -> releaseDate ?: firstAirDate
        MediaType.TV -> firstAirDate ?: releaseDate
    }
    return MediaItem(
        id = id,
        mediaType = type,
        title = resolvedTitle.orEmpty(),
        overview = overview.orEmpty(),
        posterUrl = Img.url(posterPath, Img.POSTER),
        backdropUrl = Img.url(backdropPath, Img.BACKDROP),
        voteAverage = voteAverage ?: 0.0,
        releaseDate = date,
        genreIds = genreIds.orEmpty(),
    )
}

/**
 * Maps a list of card items, dropping `person` results (only movie + tv are browsable)
 * and any entry missing both a usable title and an id.
 */
internal fun List<MediaListItemDto>.toDomain(forcedType: MediaType?): List<MediaItem> =
    asSequence()
        .filter { it.id != 0 }
        .filter { dto ->
            // For multi-search, keep only movie/tv (drop person, etc.).
            forcedType != null || dto.mediaType == "movie" || dto.mediaType == "tv"
        }
        .map { it.toDomain(forcedType) }
        .filter { it.title.isNotBlank() }
        .toList()

internal fun SeasonSummaryDto.toDomain(): Season = Season(
    seasonNumber = seasonNumber,
    name = name.orEmpty(),
    episodeCount = episodeCount,
    posterUrl = Img.url(posterPath, Img.POSTER),
    overview = overview.orEmpty(),
)

internal fun EpisodeDto.toDomain(): Episode = Episode(
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    name = name.orEmpty(),
    overview = overview.orEmpty(),
    stillUrl = Img.url(stillPath, Img.STILL),
    airDate = airDate,
    runtimeMinutes = runtime,
)

internal fun MovieDetailsDto.toDomain(): MediaDetails {
    // Movies expose imdb_id directly; fall back to external_ids if absent.
    val imdb = imdbId?.takeIf { it.isNotBlank() }
        ?: externalIds?.imdbId?.takeIf { it.isNotBlank() }

    val item = MediaItem(
        id = id,
        mediaType = MediaType.MOVIE,
        title = title ?: originalTitle.orEmpty(),
        overview = overview.orEmpty(),
        posterUrl = Img.url(posterPath, Img.POSTER),
        backdropUrl = Img.url(backdropPath, Img.BACKDROP),
        voteAverage = voteAverage ?: 0.0,
        releaseDate = releaseDate,
        genreIds = genres.map { it.id },
        imdbId = imdb,
    )
    return MediaDetails(
        item = item,
        tagline = tagline?.takeIf { it.isNotBlank() },
        runtimeMinutes = runtime,
        genres = genres.map { it.toDomain() },
        cast = credits?.cast.orEmpty()
            .sortedBy { it.order ?: Int.MAX_VALUE }
            // TMDB lists one entry PER CREDIT, so an actor with multiple roles appears twice with the
            // same person id — which duplicates the cast rows' LazyRow keys and crashes the screen.
            .distinctBy { it.id }
            .take(MAX_CAST)
            .map { it.toDomain() },
        seasons = emptyList(),
        numberOfSeasons = null,
        status = status,
        imdbId = imdb,
    )
}

internal fun TvDetailsDto.toDomain(): MediaDetails {
    // TV series get their imdb_id from external_ids.
    val imdb = externalIds?.imdbId?.takeIf { it.isNotBlank() }

    val item = MediaItem(
        id = id,
        mediaType = MediaType.TV,
        title = name ?: originalName.orEmpty(),
        overview = overview.orEmpty(),
        posterUrl = Img.url(posterPath, Img.POSTER),
        backdropUrl = Img.url(backdropPath, Img.BACKDROP),
        voteAverage = voteAverage ?: 0.0,
        releaseDate = firstAirDate,
        genreIds = genres.map { it.id },
        imdbId = imdb,
    )
    return MediaDetails(
        item = item,
        tagline = tagline?.takeIf { it.isNotBlank() },
        runtimeMinutes = episodeRunTime.firstOrNull(),
        genres = genres.map { it.toDomain() },
        cast = credits?.cast.orEmpty()
            .sortedBy { it.order ?: Int.MAX_VALUE }
            // Same dedupe as the movie mapper — one entry per credit means duplicate person ids.
            .distinctBy { it.id }
            .take(MAX_CAST)
            .map { it.toDomain() },
        // Hide the "Specials" season (number 0) from the browsable list.
        seasons = seasons
            .filter { it.seasonNumber > 0 }
            .sortedBy { it.seasonNumber }
            .map { it.toDomain() },
        numberOfSeasons = numberOfSeasons,
        status = status,
        imdbId = imdb,
        lastEpisodeToAir = lastEpisodeToAir?.toDomain(),
        nextEpisodeToAir = nextEpisodeToAir?.toDomain(),
    )
}

private fun EpisodeToAirDto.toDomain(): Episode = Episode(
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    name = name.orEmpty(),
    overview = overview.orEmpty(),
    stillUrl = Img.url(stillPath, Img.BACKDROP),
    airDate = airDate,
    runtimeMinutes = runtime,
)
