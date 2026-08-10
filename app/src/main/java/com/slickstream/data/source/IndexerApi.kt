package com.slickstream.data.source

import com.slickstream.data.source.dto.AddonCatalogEntry
import com.slickstream.data.source.dto.EztvResponseDto
import com.slickstream.data.source.dto.PirateBayRowDto
import com.slickstream.data.source.dto.StreamResponseDto
import retrofit2.http.GET
import retrofit2.http.Url

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

    /**
     * Fetch streams from an explicit, fully-built addon URL. Using [Url] (instead of a fixed Retrofit
     * base + path) lets [com.slickstream.data.source.SourceRepositoryImpl] query SEVERAL configured
     * indexer addons and merge the results — i.e. "add more sources". OkHttp keeps the ':' in a series
     * id (e.g. .../series/tt123:1:1.json) literal in the path, which Stremio routing requires.
     */
    @GET
    suspend fun getStreamsAt(@Url url: String): StreamResponseDto

    /** Fetch the community addon catalog (a JSON array) used to auto-discover working streaming addons. */
    @GET
    suspend fun getAddonCatalog(@Url url: String): List<AddonCatalogEntry>

    /** LAST-RESORT FALLBACK: query a public tracker DIRECTLY, bypassing the Stremio addon layer
     *  entirely, so an addon outage cannot look like "this title has no releases". */
    @GET
    suspend fun getPirateBay(@Url url: String): List<PirateBayRowDto>

    @GET
    suspend fun getEztv(@Url url: String): EztvResponseDto
}
