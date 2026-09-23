package com.slickstream.data.source

import com.slickstream.data.source.dto.CinemetaCatalogDto
import com.slickstream.data.source.dto.CinemetaMetaResponseDto
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Cinemeta — Stremio's IMDB-keyed metadata addon. Consulted ONLY by [ImdbIdResolver], and only for
 * titles whose TMDB record has a blank imdb id (the Netflix "Monster" anthology is the measured case:
 * TMDB splits it into four one-season shows with no imdb id, IMDB keeps it as tt13207736 S1–S4).
 *
 * Full URLs are built by the caller (like [IndexerApi]) so the percent-encoded search segment
 * (`search=Monster%3A%20The%20Lizzie%20Borden%20Story.json`) reaches Cinemeta verbatim.
 */
interface CinemetaApi {

    /** `{BASE_URL}catalog/{series|movie}/top/search=<pct-encoded query>.json` */
    @GET
    suspend fun searchCatalog(@Url url: String): CinemetaCatalogDto

    /** `{BASE_URL}meta/{series|movie}/<tt id>.json`. An unknown id redirects (307) to `{}`, so the
     *  response's `meta` is nullable. */
    @GET
    suspend fun meta(@Url url: String): CinemetaMetaResponseDto

    companion object {
        /** Must end with '/': Retrofit rejects a base URL without a trailing slash. */
        const val BASE_URL = "https://v3-cinemeta.strem.io/"
    }
}
