package com.slickstream.data.source

import com.slickstream.data.source.dto.StreamResponseDto
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Stremio/Torrentio-compatible stream resolver.
 *
 * Endpoint: GET {base}stream/{type}/{id}.json
 *  - [type] is "movie" or "series"
 *  - [id] is an IMDB id for movies (e.g. "tt1234567") and
 *    "tt1234567:season:episode" for a specific series episode.
 *
 * The base URL is supplied by [com.slickstream.data.source.di.SourceModule] and is
 * user-configurable (BuildConfig.INDEXER_BASE_URL) so the app can point at a legal source.
 */
interface IndexerApi {

    @GET("stream/{type}/{id}.json")
    suspend fun getStreams(
        @Path("type") type: String,
        // The episode id contains ':' which must NOT be percent-encoded for Stremio routing.
        @Path(value = "id", encoded = true) id: String,
    ): StreamResponseDto
}
