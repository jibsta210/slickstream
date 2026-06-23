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
) : CatalogRepository {

    private val io: CoroutineDispatcher = Dispatchers.IO

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
                val dto = if (mediaType == null) {
                    api.trendingAll(window = "week", page = page)
                } else {
                    api.trending(mediaType.path(), window = "week", page = page)
                }
                // trending/all has no media_type forcing — items carry their own discriminator.
                dto.results.toDomain(forcedType = mediaType)
            }
        }

    override suspend fun getPopular(mediaType: MediaType, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            result { api.popular(mediaType.path(), page).results.toDomain(mediaType) }
        }

    override suspend fun getTopRated(mediaType: MediaType, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            result { api.topRated(mediaType.path(), page).results.toDomain(mediaType) }
        }

    override suspend fun getNewAndUpcoming(mediaType: MediaType, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            result {
                val dto = when (mediaType) {
                    MediaType.MOVIE -> api.movieNowPlaying(page)
                    MediaType.TV -> api.tvOnTheAir(page)
                }
                dto.results.toDomain(mediaType)
            }
        }

    override suspend fun getByGenre(mediaType: MediaType, genreId: Int, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            result {
                api.discover(
                    mediaType = mediaType.path(),
                    withGenres = genreId.toString(),
                    page = page,
                ).results.toDomain(mediaType)
            }
        }

    override suspend fun getGenres(mediaType: MediaType): DataResult<List<Genre>> =
        withContext(io) {
            result { api.genres(mediaType.path()).genres.map { it.toDomain() } }
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
            result { api.similar(mediaType.path(), id).results.toDomain(mediaType) }
        }

    override suspend fun search(query: String, page: Int): DataResult<List<MediaItem>> =
        withContext(io) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@withContext DataResult.Success(emptyList())
            result {
                // forcedType = null -> mapper keeps only movie/tv from the multi results.
                api.searchMulti(trimmed, page).results.toDomain(forcedType = null)
            }
        }
}
