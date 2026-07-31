package com.slickstream.data.tmdb

import com.slickstream.core.model.DataResult
import com.slickstream.core.model.Episode
import com.slickstream.core.model.Genre
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB-backed [CatalogRepository]. All network work runs on [io]; every call is wrapped so a
 * failure surfaces as [DataResult.Error] rather than throwing. Images are already resolved to
 * full URLs by the mappers.
 */
@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val api: TmdbApi,
    private val profiles: com.slickstream.core.repository.ProfileRepository,
    private val sourceStatus: com.slickstream.data.source.SourceStatusStore,
    private val settings: com.slickstream.data.settings.SettingsRepository,
) : CatalogRepository {

    private val io: CoroutineDispatcher = Dispatchers.IO

    // Live mirror of the "hide Indian content" toggle so the sync list filters add zero latency.
    @Volatile private var hideIndian = false
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { settings.settings.collect { hideIndian = it.hideIndianContent } }
        }
    }

    /** Original-language codes treated as "Indian content" for the hide-Indian-content filter. */
    private val INDIAN_LANGS = setOf("hi", "ta", "te", "ml", "kn", "bn", "pa", "gu", "mr", "or", "as")

    /** Drop titles RECENTLY confirmed to have no playable sources (an unreleased film trending at #1
     *  must not headline the hero) AND — when the user opted in — Indian-language films/TV. Synchronous
     *  in-memory lookups, zero latency; unknown source-status passes so lists never wait on an indexer.
     *  Applied to every BROWSE list this repository returns. */
    private fun List<MediaItem>.browsable(): List<MediaItem> =
        sourceStatus.filterBrowsable(this).regionFiltered()

    /** The region filter alone — for surfaces (search) that must still hide Indian content but should
     *  NOT be source-status-filtered (hiding an explicitly searched title reads as "app doesn't have it"). */
    private fun List<MediaItem>.regionFiltered(): List<MediaItem> =
        if (!hideIndian) this
        else filterNot { it.originalLanguage?.lowercase() in INDIAN_LANGS }

    // --- Kids-profile quarantine ------------------------------------------------------------------
    // Gated HERE, in the repository, so EVERY surface inherits it — Home rows, the Movies/TV catalog
    // tabs, search, "More like this", and both the phone and TV shells — even if a screen forgets to
    // check. A kids profile only ever sees PG-13-and-below movies and TV-14-and-below shows, with
    // kid-focused rows by default.

    /** True while the ACTIVE profile is a kids profile. */
    private val kids: Boolean get() = profiles.activeProfile.value?.isKids == true

    /** id -> allowed verdict cache so search/similar filtering doesn't refetch certifications. */
    private val kidsSafeCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** DTO-level kids filter for lists that can contain ANYTHING (search, similar): keep an item only
     *  if its US certification is within the cap; unrated/unknown falls back to "has a kid genre".
     *  Certification lookups run in parallel and are cached, so repeats are free. */
    private suspend fun List<com.slickstream.data.tmdb.dto.MediaListItemDto>.filterKidsSafe(
        forcedType: MediaType?,
    ): List<com.slickstream.data.tmdb.dto.MediaListItemDto> = coroutineScope {
        map { dto ->
            async {
                val type = forcedType ?: when (dto.mediaType) {
                    "movie" -> MediaType.MOVIE
                    "tv" -> MediaType.TV
                    else -> null
                }
                dto.takeIf { type != null && kidsSafe(dto.id, type, dto.genreIds) }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun kidsSafe(id: Int, type: MediaType, genreIds: List<Int>?): Boolean {
        if (id <= 0) return false
        val key = "${type.name}:$id"
        kidsSafeCache[key]?.let { return it }
        val certVerdict: Boolean? = when (type) {
            MediaType.MOVIE -> runCatching {
                api.movieReleaseDates(id).results.firstOrNull { it.country == "US" }
                    ?.releaseDates?.firstNotNullOfOrNull { it.certification?.trim()?.takeIf(String::isNotEmpty) }
            }.getOrNull()?.let { it.uppercase() in MOVIE_KID_CERTS }
            MediaType.TV -> runCatching {
                api.tvContentRatings(id).results.firstOrNull { it.country == "US" }
                    ?.rating?.trim()?.takeIf(String::isNotEmpty)
            }.getOrNull()?.let { it.uppercase() in TV_KID_CERTS }
        }
        // Rated -> trust the rating. Unrated/unknown -> keep only clearly kid-flavoured content, and
        // NEVER when an adult-leaning genre rides along: "Animation" alone is not kid-safe (the unrated
        // animated "Saw: Rebirth" — genres Animation+Horror — leaked through a plain kid-genre check).
        val allowed = certVerdict ?: run {
            val g = genreIds.orEmpty()
            g.any { it in KID_GENRE_IDS } && g.none { it in ADULT_LEAN_GENRE_IDS }
        }
        kidsSafeCache[key] = allowed
        return allowed
    }

    private companion object {
        /** US movie certs a kids profile may see (user spec: PG-13 and below). */
        val MOVIE_KID_CERTS = setOf("G", "PG", "PG-13")

        /** US TV ratings a kids profile may see (TV-14 ≈ the PG-13 equivalent). */
        val TV_KID_CERTS = setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG", "TV-14")

        /** Kid-flavoured genres: Animation, Family, Kids(TV). */
        val KID_GENRE_IDS = setOf(16, 10751, 10762)

        /** Genres that disqualify an UNRATED item even when kid-flavoured (adult animation exists):
         *  Horror, Thriller, Crime, Mystery, War. */
        val ADULT_LEAN_GENRE_IDS = setOf(27, 53, 80, 9648, 10752)

        /** discover/tv OR-group: TMDB has NO rating filter for TV, so kids TV = these genres. */
        const val KID_TV_GENRES = "10762|10751|16"

        /** Genres a kids profile may browse (Animation/Family/Adventure/Comedy/Fantasy/Sci-Fi + TV kin). */
        val KID_BROWSE_GENRES = setOf(16, 10751, 10762, 12, 35, 14, 878, 10765)
    }

    /** Kid-safe movie discover: US certification cap at the API level. */
    private suspend fun kidMovies(
        sortBy: String,
        page: Int,
        voteFloor: Int,
        genre: Int? = null,
        releasedBefore: String? = null,
    ) = api.discover(
        mediaType = "movie",
        withGenres = genre?.toString(),
        page = page,
        sortBy = sortBy,
        certificationCountry = "US",
        certificationLte = "PG-13",
        voteCountGte = voteFloor,
        releasedBefore = releasedBefore,
    ).results.toDomain(MediaType.MOVIE).browsable()

    /** Kid-safe TV discover: TMDB can't rating-filter TV, so constrain to kid genres. A specific
     *  [genre] is ANDed with the kid OR-group ("35,10762|10751|16" = Comedy AND kid-flavoured). */
    private suspend fun kidTv(
        sortBy: String,
        page: Int,
        voteFloor: Int,
        genre: Int? = null,
        airedBefore: String? = null,
    ) = api.discover(
        mediaType = "tv",
        withGenres = genre?.let { "$it,$KID_TV_GENRES" } ?: KID_TV_GENRES,
        page = page,
        sortBy = sortBy,
        voteCountGte = voteFloor,
        airedBefore = airedBefore,
    ).results.toDomain(MediaType.TV).browsable()

    /** Alternate movie/TV so a mixed kids rail isn't all-movies-then-all-shows. */
    private fun interleave(a: List<MediaItem>, b: List<MediaItem>): List<MediaItem> = buildList {
        val ai = a.iterator(); val bi = b.iterator()
        while (ai.hasNext() || bi.hasNext()) {
            if (ai.hasNext()) add(ai.next())
            if (bi.hasNext()) add(bi.next())
        }
    }

    private inline fun <T> result(block: () -> T): DataResult<T> = try {
        DataResult.Success(block())
    } catch (c: CancellationException) {
        throw c // never swallow coroutine cancellation (e.g. the search-keystroke debounce)
    } catch (t: Throwable) {
        DataResult.Error(friendlyError(t), t)
    }

    /** Map low-level failures to messages that tell the user exactly what to do. */
    private fun friendlyError(t: Throwable): String = when {
        t is HttpException && t.code() == 401 ->
            "TMDB rejected the request (401). Add a valid TMDB_API_KEY (or TMDB_BEARER) in " +
                "local.properties, then rebuild."
        t is HttpException && t.code() == 404 -> "Not found on TMDB."
        t is HttpException && t.code() == 429 -> "TMDB rate limit reached — try again in a moment."
        t is HttpException -> "TMDB request failed (HTTP ${t.code()})."
        t is IOException -> "Can't reach TMDB. Check your internet connection and try again."
        else -> t.message ?: "TMDB request failed"
    }

    override suspend fun getTrending(mediaType: MediaType?, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            result {
                if (kids) {
                    // TMDB's trending endpoints can't be rating-filtered — build the kids equivalent
                    // from certification-capped / kid-genre discover, sorted by popularity.
                    when (mediaType) {
                        MediaType.MOVIE -> kidMovies("popularity.desc", page, voteFloor = 50)
                        MediaType.TV -> kidTv("popularity.desc", page, voteFloor = 25)
                        null -> interleave(
                            kidMovies("popularity.desc", page, voteFloor = 50),
                            kidTv("popularity.desc", page, voteFloor = 25),
                        )
                    }
                } else {
                    val dto = if (mediaType == null) {
                        api.trendingAll(window = "week", page = page)
                    } else {
                        api.trending(mediaType.path(), window = "week", page = page)
                    }
                    // trending/all has no media_type forcing — items carry their own discriminator.
                    dto.results.toDomain(forcedType = mediaType).browsable()
                }
            }
        }

    override suspend fun getPopular(mediaType: MediaType, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            result {
                if (kids) when (mediaType) {
                    MediaType.MOVIE -> kidMovies("popularity.desc", page, voteFloor = 100)
                    MediaType.TV -> kidTv("popularity.desc", page, voteFloor = 50)
                } else {
                    api.popular(mediaType.path(), page).results.toDomain(mediaType).browsable()
                }
            }
        }

    override suspend fun getTopRated(mediaType: MediaType, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            result {
                if (kids) when (mediaType) {
                    MediaType.MOVIE -> kidMovies("vote_average.desc", page, voteFloor = 300)
                    MediaType.TV -> kidTv("vote_average.desc", page, voteFloor = 150)
                } else {
                    api.topRated(mediaType.path(), page).results.toDomain(mediaType).browsable()
                }
            }
        }

    override suspend fun getNewAndUpcoming(mediaType: MediaType, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            result {
                if (kids) {
                    val today = com.slickstream.core.model.isoToday()
                    when (mediaType) {
                        MediaType.MOVIE -> kidMovies(
                            "primary_release_date.desc", page, voteFloor = 20, releasedBefore = today,
                        )
                        MediaType.TV -> kidTv(
                            "first_air_date.desc", page, voteFloor = 10, airedBefore = today,
                        )
                    }
                } else {
                    val dto = when (mediaType) {
                        MediaType.MOVIE -> api.movieNowPlaying(page)
                        MediaType.TV -> api.tvOnTheAir(page)
                    }
                    dto.results.toDomain(mediaType).browsable()
                }
            }
        }

    override suspend fun getByGenre(mediaType: MediaType, genreId: Int, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            result {
                if (kids) when (mediaType) {
                    MediaType.MOVIE -> kidMovies("popularity.desc", page, voteFloor = 30, genre = genreId)
                    MediaType.TV -> kidTv("popularity.desc", page, voteFloor = 15, genre = genreId)
                } else {
                    api.discover(
                        mediaType = mediaType.path(),
                        withGenres = genreId.toString(),
                        page = page,
                    ).results.toDomain(mediaType).browsable()
                }
            }
        }

    override suspend fun getGenres(mediaType: MediaType): DataResult<List<Genre>> =
        withContext(io) {
            result {
                val all = api.genres(mediaType.path()).genres.map { it.toDomain() }
                if (kids) all.filter { it.id in KID_BROWSE_GENRES } else all
            }
        }

    override suspend fun getDetails(id: Int, mediaType: MediaType): DataResult<MediaDetails> =
        withContext(io) {
            result {
                when (mediaType) {
                    MediaType.MOVIE -> api.movieDetails(id).toDomain()
                    MediaType.TV -> api.tvDetails(id).toDomain()
                }
            }
        }

    override suspend fun getEpisodes(tvId: Int, seasonNumber: Int): DataResult<List<Episode>> =
        withContext(io) {
            result {
                api.season(tvId, seasonNumber).episodes
                    .sortedBy { it.episodeNumber }
                    .map { it.toDomain() }
            }
        }

    override suspend fun getSimilar(id: Int, mediaType: MediaType): DataResult<List<MediaItem>> =
        withContext(io) {
            result {
                val dtos = api.similar(mediaType.path(), id).results
                val safe = if (kids) dtos.filterKidsSafe(forcedType = mediaType) else dtos
                safe.toDomain(mediaType).browsable()
            }
        }

    override suspend fun search(query: String, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@withContext DataResult.Success(emptyList())
            result {
                val dtos = api.searchMulti(trimmed, page).results
                // Kids quarantine: certification-check every hit (parallel + cached) so search can't
                // surface R/TV-MA content on a kids profile. Unrated falls back to kid-genre only.
                val safe = if (kids) dtos.filterKidsSafe(forcedType = null) else dtos
                // forcedType = null -> mapper keeps only movie/tv from the multi results.
                safe.toDomain(forcedType = null).regionFiltered()
            }
        }
}
