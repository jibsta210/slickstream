package com.slickstream.core.common

import com.slickstream.BuildConfig

/** Shared constants + small helpers used across modules. */
object Img {
    private const val BASE = BuildConfig.TMDB_IMAGE_URL // e.g. https://image.tmdb.org/t/p/

    const val POSTER = "w342"        // card art: w342 covers a <=260dp tile on 1080p/4K (was w500)
    const val POSTER_SMALL = "w342"
    const val POSTER_LARGE = "w500"  // keep for any full-size poster usage
    const val BACKDROP = "w1280"
    const val PROFILE = "w185"
    const val STILL = "w300"

    /** Build a full image URL from a TMDB relative path, or null. */
    fun url(path: String?, size: String = POSTER): String? =
        path?.takeIf { it.isNotBlank() }?.let { "$BASE$size$it" }
}

object Tmdb {
    val API_KEY: String = BuildConfig.TMDB_API_KEY
    val BEARER: String = BuildConfig.TMDB_BEARER
    val BASE_URL: String = BuildConfig.TMDB_BASE_URL
}

object Indexer {
    val BASE_URL: String = BuildConfig.INDEXER_BASE_URL
}

object Subtitles {
    val BASE_URL: String = BuildConfig.SUBTITLE_BASE_URL
}

object Sports {
    /** streamed.pk-compatible REST base. Blank disables the Live tab entirely. */
    val BASE_URL: String = BuildConfig.SPORTS_BASE_URL
}

object Auth {
    val GOOGLE_WEB_CLIENT_ID: String = BuildConfig.GOOGLE_WEB_CLIENT_ID
    /** "TVs and Limited Input devices" OAuth client — used by the Android TV device-pairing flow. */
    val GOOGLE_TV_CLIENT_ID: String = BuildConfig.GOOGLE_TV_CLIENT_ID
    /** Public client secret for the TV client (Google embeds this in app source; not truly secret). */
    val GOOGLE_TV_CLIENT_SECRET: String = BuildConfig.GOOGLE_TV_CLIENT_SECRET
}
