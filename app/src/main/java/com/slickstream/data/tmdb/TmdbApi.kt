package com.slickstream.data.tmdb

import com.slickstream.data.tmdb.dto.ContentRatingsDto
import com.slickstream.data.tmdb.dto.GenreListDto
import com.slickstream.data.tmdb.dto.MediaListItemDto
import com.slickstream.data.tmdb.dto.ReleaseDatesDto
import com.slickstream.data.tmdb.dto.MovieDetailsDto
import com.slickstream.data.tmdb.dto.PagedDto
import com.slickstream.data.tmdb.dto.SeasonDetailsDto
import com.slickstream.data.tmdb.dto.TvDetailsDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * TMDB v3 REST surface. Authentication (Bearer header or api_key query param) is injected
 * by an OkHttp interceptor configured in the DI module — callers never pass it.
 */
interface TmdbApi {

    // --- Trending ---

    /** trending/all/week — mixed movie + tv. */
    @GET("trending/all/{window}")
    suspend fun trendingAll(
        @Path("window") window: String = "week",
        @Query("page") page: Int = 1,
    ): PagedDto<MediaListItemDto>

    /** trending/{movie|tv}/week. */
    @GET("trending/{media_type}/{window}")
    suspend fun trending(
        @Path("media_type") mediaType: String,
        @Path("window") window: String = "week",
        @Query("page") page: Int = 1,
    ): PagedDto<MediaListItemDto>

    // --- Curated lists ---

    @GET("{media_type}/popular")
    suspend fun popular(
        @Path("media_type") mediaType: String,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false,
    ): PagedDto<MediaListItemDto>

    @GET("{media_type}/top_rated")
    suspend fun topRated(
        @Path("media_type") mediaType: String,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false,
    ): PagedDto<MediaListItemDto>

    /** movie/now_playing. */
    @GET("movie/now_playing")
    suspend fun movieNowPlaying(
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false,
    ): PagedDto<MediaListItemDto>

    /** tv/on_the_air. */
    @GET("tv/on_the_air")
    suspend fun tvOnTheAir(
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false,
    ): PagedDto<MediaListItemDto>

    // --- Discover / genres ---

    @GET("discover/{media_type}")
    suspend fun discover(
        @Path("media_type") mediaType: String,
        @Query("with_genres") withGenres: String? = null,
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("include_adult") includeAdult: Boolean = false,
        // Kids-profile quarantine (movie-only on TMDB): US certification cap, e.g. "PG-13".
        @Query("certification_country") certificationCountry: String? = null,
        @Query("certification.lte") certificationLte: String? = null,
        // Quality floor so date-sorted / rating-sorted discover doesn't surface junk with 3 votes.
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("primary_release_date.lte") releasedBefore: String? = null,
        @Query("first_air_date.lte") airedBefore: String? = null,
    ): PagedDto<MediaListItemDto>

    /** Per-country movie certifications (kids-profile search/similar filtering). */
    @GET("movie/{id}/release_dates")
    suspend fun movieReleaseDates(@Path("id") id: Int): ReleaseDatesDto

    /** Per-country TV content ratings (kids-profile search/similar filtering). */
    @GET("tv/{id}/content_ratings")
    suspend fun tvContentRatings(@Path("id") id: Int): ContentRatingsDto

    @GET("genre/{media_type}/list")
    suspend fun genres(
        @Path("media_type") mediaType: String,
    ): GenreListDto

    // --- Details ---

    @GET("movie/{id}")
    suspend fun movieDetails(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "credits,external_ids",
    ): MovieDetailsDto

    @GET("tv/{id}")
    suspend fun tvDetails(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "credits,external_ids",
    ): TvDetailsDto

    @GET("tv/{id}/season/{season_number}")
    suspend fun season(
        @Path("id") id: Int,
        @Path("season_number") seasonNumber: Int,
    ): SeasonDetailsDto

    // --- Similar ---

    @GET("{media_type}/{id}/similar")
    suspend fun similar(
        @Path("media_type") mediaType: String,
        @Path("id") id: Int,
        @Query("page") page: Int = 1,
    ): PagedDto<MediaListItemDto>

    // --- Search ---

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false,
    ): PagedDto<MediaListItemDto>
}
