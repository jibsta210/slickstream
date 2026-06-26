package com.slickstream.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.navigation.NavArg
import com.slickstream.navigation.Routes
import com.slickstream.tv.components.TvDestination
import com.slickstream.tv.components.TvNavRail
import com.slickstream.feature.live.LivePlayerScreen
import com.slickstream.tv.screen.TvCatalogScreen
import com.slickstream.tv.screen.TvCategoryScreen
import com.slickstream.tv.screen.TvScreenCalibrationScreen
import com.slickstream.tv.screen.TvSettingsScreen
import com.slickstream.tv.screen.TvSportsScreen
import com.slickstream.tv.screen.TvDetailsScreen
import com.slickstream.tv.screen.TvFavoritesScreen
import com.slickstream.tv.screen.TvHomeScreen
import com.slickstream.tv.screen.TvPlayerScreen
import com.slickstream.tv.screen.TvProfilePickerScreen
import com.slickstream.tv.screen.TvProfileScreen
import com.slickstream.tv.screen.TvSearchScreen
import com.slickstream.ui.theme.Brand

/**
 * Root Android TV composable, referenced from [com.slickstream.MainActivity].
 *
 * Layout: a persistent left [TvNavRail] (Browse / Search / Favorites / Profile) beside a
 * navigation-compose [NavHost] that renders the active section plus the full-screen details and
 * player routes (which hide the rail). Everything is reachable with a D-pad only. Existing
 * feature ViewModels are reused on every screen — no data logic is duplicated here.
 */
@Composable
fun TvApp() {
    SlickStreamTvTheme {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route

        // No launch picker: the app reopens straight into the last-used profile (ProfileRepository
        // persists activeProfileId across launches). Switch via Profile → Switch profile.

        // The rail is shown for the four top-level sections; details + player are immersive.
        val showRail = currentRoute == null ||
            currentRoute == Routes.HOME ||
            currentRoute == Routes.MOVIES ||
            currentRoute == Routes.TV ||
            currentRoute == Routes.SPORTS ||
            currentRoute == Routes.SEARCH ||
            currentRoute == Routes.FAVORITES ||
            currentRoute == Routes.PROFILE

        // Resolve which rail item is highlighted from the current route.
        val selectedRoute = when (currentRoute) {
            Routes.MOVIES -> Routes.MOVIES
            Routes.TV -> Routes.TV
            Routes.SPORTS -> Routes.SPORTS
            Routes.SEARCH -> Routes.SEARCH
            Routes.FAVORITES -> Routes.FAVORITES
            Routes.PROFILE -> Routes.PROFILE
            else -> Routes.HOME
        }

        fun openDetails(item: MediaItem) {
            navController.navigate(Routes.details(item.mediaType, item.id))
        }

        fun openPlayer(type: MediaType, id: Int, season: Int? = null, episode: Int? = null) {
            navController.navigate(Routes.player(type, id, season, episode))
        }

        fun openCategory(type: MediaType, genreId: Int, name: String) {
            navController.navigate(Routes.category(type, genreId, name))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brand.Background),
        ) {
            Row(Modifier.fillMaxSize()) {
                if (showRail) {
                    TvNavRail(
                        selectedRoute = selectedRoute,
                        onSelect = { dest -> navigateTopLevel(navController, dest) },
                    )
                }

                // ZERO shell inset on EVERY route. Any inset here framed the content (player video,
                // detail/home backdrops, all of it) in dark blue-grey Brand.Background (#0B0B0F) — the
                // UI-wide "blue-grey box / whole app scaled" bug. Backgrounds, backdrops and video now
                // bleed to the physical edge; 10-foot safe-area is the responsibility of each screen's
                // own foreground padding (rows/text), which is invisible because the menu background is
                // the same colour that fills the shell.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize(),
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable(Routes.HOME) {
                            TvHomeScreen(
                                onMediaClick = ::openDetails,
                                onPlayClick = { item -> openPlayer(item.mediaType, item.id) },
                                // Resume carries the saved season/episode so TV shows find episode
                                // sources (plain onPlayClick would play the show with no episode). A
                                // FINISHED show episode resumes the NEXT one, so a series you're working
                                // through continues forward instead of replaying what you just watched.
                                onResume = { h ->
                                    val ep = if (h.media.mediaType == MediaType.TV && h.progress.isFinished)
                                        (h.progress.episode ?: 1) + 1 else h.progress.episode
                                    openPlayer(h.media.mediaType, h.media.id, h.progress.season, ep)
                                },
                            )
                        }

                        composable(Routes.MOVIES) {
                            TvCatalogScreen(
                                mediaType = MediaType.MOVIE,
                                onMediaClick = ::openDetails,
                                onPlayClick = { item -> openPlayer(item.mediaType, item.id) },
                                onCategoryClick = { gid, name -> openCategory(MediaType.MOVIE, gid, name) },
                            )
                        }

                        composable(Routes.TV) {
                            TvCatalogScreen(
                                mediaType = MediaType.TV,
                                onMediaClick = ::openDetails,
                                onPlayClick = { item -> openPlayer(item.mediaType, item.id) },
                                onCategoryClick = { gid, name -> openCategory(MediaType.TV, gid, name) },
                            )
                        }

                        composable(
                            route = Routes.CATEGORY,
                            arguments = listOf(
                                navArgument(NavArg.MEDIA_TYPE) { type = NavType.StringType },
                                navArgument(NavArg.GENRE_ID) { type = NavType.IntType },
                                navArgument(NavArg.GENRE_NAME) { type = NavType.StringType },
                            ),
                        ) { entry ->
                            val a = entry.arguments
                            val type = runCatching {
                                MediaType.valueOf(a?.getString(NavArg.MEDIA_TYPE) ?: MediaType.MOVIE.name)
                            }.getOrDefault(MediaType.MOVIE)
                            TvCategoryScreen(
                                mediaType = type,
                                genreId = a?.getInt(NavArg.GENRE_ID) ?: -1,
                                genreName = a?.getString(NavArg.GENRE_NAME).orEmpty(),
                                onMediaClick = ::openDetails,
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable(Routes.SEARCH) {
                            TvSearchScreen(onMediaClick = ::openDetails)
                        }

                        composable(Routes.SPORTS) {
                            TvSportsScreen(onOpenPlayer = { navController.navigate(Routes.LIVE) })
                        }

                        composable(Routes.LIVE) {
                            LivePlayerScreen(onBack = { navController.popBackStack() })
                        }

                        composable(Routes.FAVORITES) {
                            TvFavoritesScreen(onMediaClick = ::openDetails)
                        }

                        composable(Routes.PROFILE) {
                            TvProfileScreen(
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                onSwitchProfile = { navController.navigate(Routes.PROFILE_PICKER) },
                            )
                        }

                        composable(Routes.PROFILE_PICKER) {
                            TvProfilePickerScreen(
                                onProfileChosen = {
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }

                        composable(Routes.SETTINGS) {
                            TvSettingsScreen(
                                onOpenCalibration = { navController.navigate(Routes.SCREEN_CALIBRATION) },
                            )
                        }

                        composable(Routes.SCREEN_CALIBRATION) {
                            TvScreenCalibrationScreen(onBack = { navController.popBackStack() })
                        }

                        composable(
                            route = Routes.DETAILS,
                            arguments = listOf(
                                navArgument(NavArg.MEDIA_TYPE) { type = NavType.StringType },
                                navArgument(NavArg.MEDIA_ID) { type = NavType.StringType },
                            ),
                        ) {
                            TvDetailsScreen(
                                onPlay = { type, id, season, episode ->
                                    openPlayer(type, id, season, episode)
                                },
                                onMediaClick = ::openDetails,
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable(
                            route = Routes.PLAYER,
                            arguments = listOf(
                                navArgument(NavArg.MEDIA_TYPE) { type = NavType.StringType },
                                navArgument(NavArg.MEDIA_ID) { type = NavType.IntType },
                                navArgument(NavArg.SEASON) {
                                    type = NavType.IntType; defaultValue = -1
                                },
                                navArgument(NavArg.EPISODE) {
                                    type = NavType.IntType; defaultValue = -1
                                },
                            ),
                        ) {
                            TvPlayerScreen(
                                onBack = { navController.popBackStack() },
                                // End-of-movie autoplay: replace the current player on the back stack so
                                // back returns to where the user started, not a chain of finished movies.
                                onPlayMedia = { type, id ->
                                    navController.navigate(Routes.player(type, id)) {
                                        popUpTo(Routes.PLAYER) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                // End-of-series suggestion: open the picked show's details (you choose
                                // where to start). Replace the finished player so Back returns to browse.
                                onOpenDetails = { type, id ->
                                    navController.navigate(Routes.details(type, id)) {
                                        popUpTo(Routes.PLAYER) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Navigate to a top-level section, keeping a single instance on the back stack. */
private fun navigateTopLevel(
    navController: androidx.navigation.NavController,
    dest: TvDestination,
) {
    navController.navigate(dest.route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
