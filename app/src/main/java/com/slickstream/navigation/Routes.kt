package com.slickstream.navigation

import android.net.Uri
import com.slickstream.core.model.MediaType

/**
 * Central navigation contract shared by feature screens and the NavHost wiring.
 * Phone navigation uses these string routes with Navigation-Compose.
 */
object Routes {
    const val HOME = "home"
    const val MOVIES = "movies"
    const val TV = "tv"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val LOGIN = "login"

    // details/{mediaType}/{mediaId}
    const val DETAILS = "details/{mediaType}/{mediaId}"
    fun details(type: MediaType, id: Int) = "details/${type.name}/$id"

    // player/{mediaType}/{mediaId}?season={season}&episode={episode}
    const val PLAYER = "player/{mediaType}/{mediaId}?season={season}&episode={episode}"
    fun player(type: MediaType, id: Int, season: Int? = null, episode: Int? = null) =
        "player/${type.name}/$id?season=${season ?: -1}&episode=${episode ?: -1}"

    // category/{mediaType}/{genreId}/{genreName} — full grid for one genre ("Kids", "Action", …)
    const val CATEGORY = "category/{mediaType}/{genreId}/{genreName}"
    fun category(type: MediaType, genreId: Int, name: String) =
        "category/${type.name}/$genreId/${Uri.encode(name)}"

    // Live sports — top-level tab + the HLS live player.
    const val SPORTS = "sports"
    const val LIVE = "live"
}

/** Nav argument keys (kept in one place so screens + host agree). */
object NavArg {
    const val MEDIA_TYPE = "mediaType"
    const val MEDIA_ID = "mediaId"
    const val SEASON = "season"
    const val EPISODE = "episode"
    const val QUERY = "query"
    const val GENRE_ID = "genreId"
    const val GENRE_NAME = "genreName"
}

/** Bottom-nav destinations for phone: Home, Movies, TV, Favourites. (Search + Profile are
 *  reached from the top bar.) */
enum class TopLevelDestination(val route: String, val label: String) {
    HOME(Routes.HOME, "Home"),
    MOVIES(Routes.MOVIES, "Movies"),
    TV(Routes.TV, "TV"),
    SPORTS(Routes.SPORTS, "Sports"),
    FAVORITES(Routes.FAVORITES, "Favourites"),
}
