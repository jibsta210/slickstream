package com.slickstream.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.feature.auth.AuthViewModel
import com.slickstream.feature.catalog.CatalogScreen
import com.slickstream.feature.details.DetailsScreen
import com.slickstream.feature.favorites.FavoritesScreen
import com.slickstream.feature.home.HomeScreen
import com.slickstream.feature.player.PlayerScreen
import com.slickstream.feature.catalog.CategoryScreen
import com.slickstream.core.model.Profile
import com.slickstream.feature.live.LivePlayerScreen
import com.slickstream.feature.profile.ProfilePickerScreen
import com.slickstream.feature.profile.ProfileScreen
import com.slickstream.feature.profile.ProfileViewModel
import com.slickstream.ui.components.ProfileAvatar
import com.slickstream.feature.sports.SportsScreen
import com.slickstream.feature.settings.SettingsScreen
import com.slickstream.feature.search.SearchUiState
import com.slickstream.feature.search.SearchViewModel
import com.slickstream.feature.search.VoiceState
import com.slickstream.navigation.NavArg
import com.slickstream.navigation.Routes
import com.slickstream.navigation.TopLevelDestination
import com.slickstream.ui.components.ErrorRetry
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.components.PosterCard
import com.slickstream.ui.theme.Brand

/**
 * Root composable for the phone / tablet form factor. Hosts a Material3 [Scaffold] with a bottom
 * [NavigationBar] for the four top-level destinations (Home, Search, Favorites, Profile) and a
 * [NavHost] that also drives the full-screen Details, Player and Login routes (where the bottom
 * bar is hidden).
 *
 * Sign-in state is read from the shared [AuthViewModel]; the "Sign in" affordances on Favourites /
 * Profile launch the real Google credential flow via [AuthViewModel.signIn], falling back to the
 * dedicated [Routes.LOGIN] screen when no hosting Activity is available.
 */
@Composable
fun PhoneApp() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val user by authViewModel.user.collectAsStateWithLifecycle()
    val isSignedIn = user != null

    // Active profile drives the top-bar avatar (colour + initial). The picker is its own route.
    val profileViewModel: ProfileViewModel = hiltViewModel()
    val profiles by profileViewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by profileViewModel.activeProfile.collectAsStateWithLifecycle()

    // No launch picker: reopen straight into the last-used profile (activeProfileId is persisted).
    // Switch from the Profile screen → Switch profile.

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // Launch the Google credential flow if we can host the system UI; otherwise route to the
    // full-screen login experience.
    val onSignIn: () -> Unit = {
        val act = activity
        if (act != null) authViewModel.signIn(act)
        else navController.navigateToTopLevelSafe(Routes.LOGIN)
    }

    // Surface sign-in errors from the shared AuthViewModel (the Profile/Favourites buttons drive
    // this instance, which no individual screen renders) as a snackbar.
    val authUi by authViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(authUi.errorMessage) {
        authUi.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            authViewModel.clearError()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in CHROME_ROUTES
    val showTopBar = currentRoute in TOP_LEVEL_ROUTES

    Scaffold(
        containerColor = Brand.Background,
        contentColor = Brand.OnSurface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = showTopBar,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
            ) {
                TopBar(
                    activeProfile = activeProfile,
                    onSearch = { navController.navigate(Routes.SEARCH) { launchSingleTop = true } },
                    // The avatar opens the account hub (Settings, sign-in/out, Link TV, switch
                    // profile). The "Switch profile" row there reaches the picker — going straight to
                    // the picker used to hide Settings entirely.
                    onProfile = { navController.navigate(Routes.PROFILE) { launchSingleTop = true } },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BottomBar(
                    currentDestination = backStackEntry,
                    onSelect = { destination ->
                        navController.navigateToTopLevel(destination)
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onMediaClick = { item -> navController.navigateToDetails(item) },
                    onPlayClick = { item -> navController.navigateToPlayer(item) },
                    onResumeClick = { history -> navController.navigateToPlayer(history) },
                    onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                )
            }

            composable(Routes.MOVIES) {
                CatalogScreen(
                    mediaType = MediaType.MOVIE,
                    onMediaClick = { item -> navController.navigateToDetails(item) },
                    onPlayClick = { item -> navController.navigateToPlayer(item) },
                    onCategoryClick = { genreId, name ->
                        navController.navigate(Routes.category(MediaType.MOVIE, genreId, name))
                    },
                )
            }

            composable(Routes.TV) {
                CatalogScreen(
                    mediaType = MediaType.TV,
                    onMediaClick = { item -> navController.navigateToDetails(item) },
                    onPlayClick = { item -> navController.navigateToPlayer(item) },
                    onCategoryClick = { genreId, name ->
                        navController.navigate(Routes.category(MediaType.TV, genreId, name))
                    },
                )
            }

            composable(Routes.SEARCH) {
                SearchScreen(
                    onMediaClick = { item -> navController.navigateToDetails(item) },
                )
            }

            composable(Routes.SPORTS) {
                SportsScreen(
                    onOpenPlayer = { navController.navigate(Routes.LIVE) },
                )
            }

            composable(Routes.LIVE) {
                LivePlayerScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.FAVORITES) {
                FavoritesScreen(
                    onMediaClick = { item -> navController.navigateToDetails(item) },
                    isSignedIn = isSignedIn,
                    onSignIn = onSignIn,
                )
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    onSignIn = onSignIn,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                    onSwitchProfile = { navController.navigate(Routes.PROFILE_PICKER) { launchSingleTop = true } },
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigateToTopLevel(TopLevelDestination.entries.first())
                        }
                    },
                )
            }

            composable(Routes.PROFILE_PICKER) {
                ProfilePickerScreen(
                    onProfileChosen = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.HOME) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DOWNLOADS) {
                com.slickstream.feature.downloads.DownloadsScreen(
                    onBack = { navController.popBackStack() },
                    onPlay = { type, id, season, episode ->
                        navController.navigate(Routes.player(type, id, season, episode))
                    },
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
                val args = entry.arguments
                val type = runCatching {
                    MediaType.valueOf(args?.getString(NavArg.MEDIA_TYPE) ?: MediaType.MOVIE.name)
                }.getOrDefault(MediaType.MOVIE)
                CategoryScreen(
                    mediaType = type,
                    genreId = args?.getInt(NavArg.GENRE_ID) ?: -1,
                    genreName = args?.getString(NavArg.GENRE_NAME).orEmpty(),
                    onMediaClick = { item -> navController.navigateToDetails(item) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.DETAILS,
                arguments = listOf(
                    navArgument(NavArg.MEDIA_TYPE) { type = NavType.StringType },
                    // DetailsViewModel reads MEDIA_ID as a String first (toIntOrNull); keep it String.
                    navArgument(NavArg.MEDIA_ID) { type = NavType.StringType },
                ),
            ) {
                DetailsScreen(
                    onPlay = { mediaType, mediaId, season, episode ->
                        navController.navigate(Routes.player(mediaType, mediaId, season, episode))
                    },
                    onStartOver = { mediaType, mediaId ->
                        navController.navigate(Routes.player(mediaType, mediaId, startOver = true))
                    },
                    onBack = { navController.popBackStack() },
                    onMediaClick = { item -> navController.navigateToDetails(item) },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument(NavArg.MEDIA_TYPE) { type = NavType.StringType },
                    // PlayerViewModel reads MEDIA_ID / SEASON / EPISODE as Int.
                    navArgument(NavArg.MEDIA_ID) { type = NavType.IntType },
                    navArgument(NavArg.SEASON) {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                    navArgument(NavArg.EPISODE) {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                    navArgument(NavArg.START_OVER) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) {
                PlayerScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.LOGIN) {
                com.slickstream.feature.auth.LoginScreen(
                    onSignedIn = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onSkip = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}

// --- Bottom navigation ------------------------------------------------------

private val TOP_LEVEL_ROUTES = TopLevelDestination.entries.map { it.route }.toSet()

/** Keep the bottom bar visible on the 4 tabs plus the top-bar-reached Search / Profile screens. */
private val CHROME_ROUTES = TOP_LEVEL_ROUTES + setOf(Routes.SEARCH, Routes.PROFILE)

// --- Top app bar (wordmark + search + profile avatar) -----------------------

@Composable
private fun TopBar(
    activeProfile: Profile?,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand.Background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SlickStream",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Brand.OnSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "Search",
                tint = Brand.OnSurface,
            )
        }
        Spacer(Modifier.size(6.dp))
        TopBarAvatar(profile = activeProfile, onClick = onProfile)
    }
}

@Composable
private fun TopBarAvatar(profile: Profile?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (profile != null) {
            ProfileAvatar(
                name = profile.name,
                colorIndex = profile.colorIndex,
                isKids = profile.isKids,
                avatarIndex = profile.avatarIndex,
                size = 36.dp,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brand.SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = "Profile",
                    tint = Brand.OnSurfaceDim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private fun iconFor(destination: TopLevelDestination): ImageVector = when (destination) {
    TopLevelDestination.HOME -> Icons.Rounded.Home
    TopLevelDestination.MOVIES -> Icons.Rounded.Movie
    TopLevelDestination.TV -> Icons.Rounded.Tv
    TopLevelDestination.SPORTS -> Icons.Rounded.SportsBasketball
    TopLevelDestination.FAVORITES -> Icons.Rounded.Favorite
}

@Composable
private fun BottomBar(
    currentDestination: NavBackStackEntry?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar(
        containerColor = Brand.Surface,
        contentColor = Brand.OnSurface,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = currentDestination?.destination?.hierarchy
                ?.any { it.route == destination.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = iconFor(destination),
                        contentDescription = destination.label,
                    )
                },
                label = {
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Brand.Violet,
                    selectedTextColor = Brand.Violet,
                    indicatorColor = Brand.Violet.copy(alpha = 0.16f),
                    unselectedIconColor = Brand.OnSurfaceDim,
                    unselectedTextColor = Brand.OnSurfaceDim,
                ),
            )
        }
    }
}

// --- Navigation helpers -----------------------------------------------------

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        // Pop to the start destination so the back stack doesn't grow when tab-switching, and
        // restore each tab's own scroll/selection state.
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/** Plain navigate used for non-top-level routes (e.g. login) without back-stack juggling. */
private fun NavHostController.navigateToTopLevelSafe(route: String) {
    navigate(route) { launchSingleTop = true }
}

private fun NavHostController.navigateToDetails(item: MediaItem) {
    navigate(Routes.details(item.mediaType, item.id))
}

private fun NavHostController.navigateToPlayer(item: MediaItem) {
    // Hero / direct-play. TV without an explicit episode defaults to S1E1 in the player chain.
    val (season, episode) = if (item.mediaType == MediaType.TV) 1 to 1 else null to null
    navigate(Routes.player(item.mediaType, item.id, season, episode))
}

private fun NavHostController.navigateToPlayer(history: WatchHistoryItem) {
    // continueWatching is pre-resolved in HomeViewModel (the finished->next-episode roll happens there),
    // so resume exactly the episode the tile shows.
    navigate(
        Routes.player(
            history.media.mediaType,
            history.media.id,
            history.progress.season,
            history.progress.episode,
        )
    )
}

// --- Search screen ----------------------------------------------------------
//
// The search module shipped a SearchViewModel + VoiceSearchManager but no Composable screen, so
// the app shell owns the search UI here. It consumes SearchViewModel exactly as provided and
// reuses the shared ui-kit components (PosterCard / LoadingState / ErrorRetry).

@Composable
private fun SearchScreen(
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Request RECORD_AUDIO on demand, then start listening once granted.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startVoice()
    }
    val onMicClick: () -> Unit = {
        if (voiceState.isListening) {
            viewModel.stopVoice()
        } else if (viewModel.voiceSearch.hasPermission()) {
            viewModel.startVoice()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Release the recognizer when the screen leaves composition.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopVoice() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SearchField(
            query = state.query,
            isListening = voiceState.isListening,
            voiceAvailable = voiceState.available,
            onQueryChange = viewModel::setQuery,
            onSubmit = viewModel::submit,
            onClear = viewModel::clear,
            onMicClick = onMicClick,
        )

        voiceState.error?.let { err ->
            VoiceHint(text = err)
        }

        SearchBody(
            state = state,
            voiceState = voiceState,
            onMediaClick = onMediaClick,
            onRetry = viewModel::submit,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    isListening: Boolean,
    voiceAvailable: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onMicClick: () -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp)),
        placeholder = { Text("Search movies & shows", color = Brand.OnSurfaceDim) },
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = Brand.OnSurfaceDim,
            )
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = Brand.OnSurfaceDim,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onClear)
                            .padding(4.dp),
                    )
                }
                if (voiceAvailable) {
                    MicButton(isListening = isListening, onClick = onMicClick)
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Brand.Surface,
            unfocusedContainerColor = Brand.Surface,
            disabledContainerColor = Brand.Surface,
            focusedTextColor = Brand.OnSurface,
            unfocusedTextColor = Brand.OnSurface,
            cursorColor = Brand.Violet,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun MicButton(isListening: Boolean, onClick: () -> Unit) {
    // Pulsing ring while listening, static otherwise.
    val transition = rememberInfiniteTransition(label = "mic-pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-scale",
    )
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(36.dp)
            .scale(if (isListening) pulse else 1f)
            .clip(CircleShape)
            .background(if (isListening) Brand.Violet else Brand.SurfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = if (isListening) "Stop voice search" else "Voice search",
            tint = if (isListening) Color.White else Brand.Cyan,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun VoiceHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Brand.Star,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

@Composable
private fun SearchBody(
    state: SearchUiState,
    voiceState: VoiceState,
    onMediaClick: (MediaItem) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.errorMessage != null -> ErrorRetry(
            message = state.errorMessage!!,
            onRetry = onRetry,
            modifier = Modifier.fillMaxSize(),
        )

        state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize())

        state.isEmpty -> SearchMessage(
            title = "No results",
            message = "Nothing matched “${state.query}”. Try another title.",
        )

        state.isIdle -> SearchMessage(
            title = if (voiceState.isListening) "Listening…" else "Find something to watch",
            message = if (voiceState.isListening) {
                "Say the name of a movie or show."
            } else {
                "Search by title, or tap the mic to search with your voice."
            },
        )

        else -> SearchResultsGrid(items = state.results, onMediaClick = onMediaClick)
    }
}

@Composable
private fun SearchResultsGrid(
    items: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { "${it.mediaType.name}-${it.id}" }) { item ->
            PosterCard(
                item = item,
                onClick = onMediaClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SearchMessage(title: String, message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = Brand.Violet,
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Brand.OnSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.OnSurfaceDim,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- misc -------------------------------------------------------------------

/** Unwrap the hosting [Activity] from a (possibly wrapped) Compose [Context]. */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
