package com.slickstream.tv.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import com.slickstream.core.model.Episode
import com.slickstream.core.model.hasAired
import com.slickstream.core.model.isDownloadable
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.Season
import com.slickstream.feature.details.DetailsUiState
import com.slickstream.feature.details.DetailsViewModel
import com.slickstream.tv.components.TvMediaRow
import com.slickstream.ui.components.RatingBadge
import com.slickstream.ui.theme.Brand

/**
 * Android TV details screen. Reuses [DetailsViewModel] (same TMDB-backed data as the phone
 * details screen) — it reads the media type + id straight from the nav route via
 * `SavedStateHandle`, so the TV layer only supplies callbacks.
 *
 * Layout: full-bleed backdrop, metadata + Play / Favorite actions, then a focusable season
 * selector + episode list for TV shows, and a "More like this" row. Fully D-pad navigable.
 *
 * @param onPlay (type, id, season, episode) — start playback. season/episode are null for movies.
 */
@Composable
fun TvDetailsScreen(
    onPlay: (MediaType, Int, Int?, Int?) -> Unit,
    onStartOver: (MediaType, Int, Int?, Int?) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val seasonDownloads by viewModel.seasonDownloads.collectAsStateWithLifecycle()

    when {
        state.isLoading && state.details == null -> TvLoading(modifier)
        state.errorMessage != null && state.details == null ->
            TvErrorRetry(message = state.errorMessage!!, onRetry = viewModel::load, modifier = modifier)
        state.details != null -> DetailsContent(
            state = state,
            isFavorite = isFavorite,
            downloadState = downloadState,
            seasonDownloads = seasonDownloads,
            onPlay = onPlay,
            onStartOver = onStartOver,
            onToggleFavorite = viewModel::toggleFavorite,
            onSelectSeason = viewModel::selectSeason,
            onMediaClick = onMediaClick,
            onMarkEpisodeWatched = viewModel::markWatched,
            onMarkEpisodeUnwatched = viewModel::markUnwatched,
            onMarkMovieWatched = viewModel::markMovieWatched,
            onMarkMovieUnwatched = viewModel::markMovieUnwatched,
            onDownloadMovie = viewModel::downloadMovie,
            onDownloadSeason = viewModel::downloadSeason,
            onDownloadEpisode = { ep -> viewModel.downloadEpisode(ep.seasonNumber, ep.episodeNumber, ep.name) },
            modifier = modifier,
        )
    }
}

@Composable
private fun DetailsContent(
    state: DetailsUiState,
    isFavorite: Boolean,
    downloadState: com.slickstream.core.model.Download?,
    seasonDownloads: Map<Int, com.slickstream.core.model.Download>,
    onPlay: (MediaType, Int, Int?, Int?) -> Unit,
    onStartOver: (MediaType, Int, Int?, Int?) -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onMarkEpisodeWatched: (season: Int, episode: Int) -> Unit,
    onMarkEpisodeUnwatched: (season: Int, episode: Int) -> Unit,
    onMarkMovieWatched: () -> Unit,
    onMarkMovieUnwatched: () -> Unit,
    onDownloadMovie: () -> Unit,
    onDownloadSeason: () -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val details = state.details ?: return
    val item = details.item

    // No screen-level background — TvApp's shell already fills Brand.Background.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The hero (title -> Play) must fit the VIEWPORT. A fixed 560dp backdrop + 220dp top inset was
        // taller than a 960x540dp TV, so focusing Play scrolled the list and pushed the TITLE off the
        // top — the "description starts at the very top, no title" bug. Derive both from the height.
        val shortViewport = maxHeight < 620.dp
        val backdropHeight = (maxHeight * 0.78f).coerceIn(240.dp, 560.dp)
        val heroTopInset = if (shortViewport) 48.dp else (maxHeight * 0.28f).coerceIn(56.dp, 220.dp)
        // Backdrop fills the top of the screen, fading into the background.
        val context = LocalContext.current
        val backdropRequest = remember(item.backdropUrl, item.posterUrl) {
            ImageRequest.Builder(context)
                .data(item.backdropUrl ?: item.posterUrl)
                .size(1280, 720) // hard decode ceiling so a 4K panel doesn't decode at 2x
                .scale(Scale.FILL)
                .build()
        }
        AsyncImage(
            model = backdropRequest,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(backdropHeight),
        )
        // Both scrims in ONE draw node, bounded to the 560dp hero (the horizontal one used to be a
        // fillMaxSize layer under the scrolling list — a full-screen composite re-blended every
        // scroll frame for a gradient that's transparent past 60% anyway).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(backdropHeight)
                .drawWithCache {
                    val vertical = Brush.verticalGradient(
                        0.3f to Color.Transparent,
                        1f to Brand.Background,
                        endY = size.height,
                    )
                    val horizontal = Brush.horizontalGradient(
                        0f to Color(0xCC0B0B0F),
                        0.6f to Color.Transparent,
                        endX = size.width,
                    )
                    onDrawBehind {
                        drawRect(horizontal)
                        drawRect(vertical)
                    }
                },
        )

        // Landing focus on Play makes Compose bringIntoView-scroll the list, which ate the top inset
        // and clipped the TITLE. The hero fits the viewport, so once focus has settled we simply undo
        // any offset WITHIN the hero item — the title keeps its margin and Play stays visible/focused.
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.runtime.LaunchedEffect(item.id) {
            repeat(6) {
                kotlinx.coroutines.delay(150)
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 0) {
                    runCatching { listState.scrollToItem(0) }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = heroTopInset, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (shortViewport) 18.dp else 28.dp),
        ) {
            item(key = "hero") {
                HeroBlock(
                    compact = shortViewport,
                    state = state,
                    isFavorite = isFavorite,
                    downloadState = downloadState,
                    seasonDownloads = seasonDownloads,
                    onPlay = onPlay,
                    onStartOver = onStartOver,
                    onToggleFavorite = onToggleFavorite,
                    onMarkMovieWatched = onMarkMovieWatched,
                    onMarkMovieUnwatched = onMarkMovieUnwatched,
                    onDownloadMovie = onDownloadMovie,
                    onDownloadSeason = onDownloadSeason,
                )
            }

            if (state.isTv && state.seasons.isNotEmpty()) {
                // Only show the season picker when there's an actual choice. A single-season show (a
                // limited series / miniseries) rendered one pointless focusable chip labelled with the
                // season's name ("Limited Series", "Miniseries"…) that selected the only season.
                if (state.seasons.size > 1) {
                    item(key = "seasons") {
                        SeasonSelector(
                            seasons = state.seasons,
                            selected = state.selectedSeasonNumber,
                            onSelect = onSelectSeason,
                        )
                    }
                }
                item(key = "episodes") {
                    EpisodeList(
                        state = state,
                        downloads = seasonDownloads,
                        onDownloadEpisode = onDownloadEpisode,
                        onPlayEpisode = { ep ->
                            onPlay(MediaType.TV, item.id, ep.seasonNumber, ep.episodeNumber)
                        },
                        onToggleWatched = { ep, watched ->
                            if (watched) {
                                onMarkEpisodeUnwatched(ep.seasonNumber, ep.episodeNumber)
                            } else {
                                onMarkEpisodeWatched(ep.seasonNumber, ep.episodeNumber)
                            }
                        },
                    )
                }
            }

            if (details.cast.isNotEmpty()) {
                item(key = "cast") { TvCastRow(cast = details.cast) }
            }

            if (state.similar.isNotEmpty()) {
                item(key = "similar") {
                    TvMediaRow(
                        title = "More Like This",
                        items = state.similar,
                        onItemClick = onMediaClick,
                    )
                }
            }
        }
    }
}

/** D-pad-navigable cast row for the TV details screen — the phone details had one, the TV didn't, from
 *  data the DetailsViewModel already fetches. Circular headshots, focusable cards. */
@Composable
private fun TvCastRow(cast: List<com.slickstream.core.model.CastMember>) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.titleLarge,
            color = Brand.OnSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 48.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
        ) {
            items(cast, key = { it.id }, contentType = { "cast" }) { member ->
                val shape = RoundedCornerShape(14.dp)
                Surface(
                    onClick = {},   // inert but focusable so the D-pad can traverse the row
                    shape = ClickableSurfaceDefaults.shape(shape = shape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Brand.Surface,
                        focusedContainerColor = Brand.Surface,
                        contentColor = Brand.OnSurface,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet), shape = shape),
                    ),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
                    modifier = Modifier.width(124.dp),
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        coil.compose.AsyncImage(
                            model = member.profileUrl,
                            contentDescription = member.name,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .size(92.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Brand.SurfaceVariant),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = member.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (member.character.isNotBlank()) {
                            Text(
                                text = member.character,
                                style = MaterialTheme.typography.labelSmall,
                                color = Brand.OnSurfaceDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBlock(
    /** Short viewport (e.g. 960x540dp TV): tighten spacing + overview so title..Play all fit. */
    compact: Boolean = false,
    state: DetailsUiState,
    isFavorite: Boolean,
    downloadState: com.slickstream.core.model.Download?,
    seasonDownloads: Map<Int, com.slickstream.core.model.Download>,
    onPlay: (MediaType, Int, Int?, Int?) -> Unit,
    onStartOver: (MediaType, Int, Int?, Int?) -> Unit,
    onToggleFavorite: () -> Unit,
    onMarkMovieWatched: () -> Unit,
    onMarkMovieUnwatched: () -> Unit,
    onDownloadMovie: () -> Unit,
    onDownloadSeason: () -> Unit,
) {
    val details = state.details ?: return
    val item = details.item

    // Details hides the rail — land focus on Play ONCE when this screen opens so the first D-pad press
    // isn't swallowed. Keyed on Unit (not item.id): a "More like this" jump opens a NEW details screen
    // whose own effect focuses its Play, while re-keying on item.id could yank focus off the episode list
    // you were browsing.
    val playFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(20) {
            kotlinx.coroutines.delay(40)
            if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.62f)
            .padding(start = 48.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Tagline is the first thing to go on a short viewport — it buys the vertical slack that keeps
        // the TITLE on screen when focus scrolls the list to reveal Play.
        if (!compact) {
            details.tagline?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.Cyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.onlyCamAvailable) {
                com.slickstream.ui.components.CamBadge()
                Spacer(Modifier.width(12.dp))
            }
            if (item.voteAverage > 0.0) {
                RatingBadge(vote = item.voteAverage)
                Spacer(Modifier.width(12.dp))
            }
            item.year?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
                Spacer(Modifier.width(12.dp))
            }
            metaLine(details)?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
            }
        }

        if (details.genres.isNotEmpty()) {
            Text(
                text = details.genres.joinToString("  ·  ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.OnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = item.overview,
            style = MaterialTheme.typography.bodyLarge,
            color = Brand.OnSurface.copy(alpha = 0.92f),
            maxLines = if (compact) 2 else 4,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(6.dp))

        // Keep the primary movie actions on one row and the maintenance actions on a second row.
        // Five wide buttons in one Row overflow on 720p / high-density Google TV layouts.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DetailsActionButton(
                    onClick = {
                        val rt = state.resumeTarget
                        if (state.isTv) {
                            // Resume the in-progress episode, or the next one after the last you finished
                            // (computed in the VM) — not always S1E1.
                            val season = rt?.season ?: state.selectedSeasonNumber
                                ?: state.seasons.firstOrNull()?.seasonNumber
                            onPlay(MediaType.TV, item.id, season, rt?.episode ?: 1)
                        } else {
                            onPlay(MediaType.MOVIE, item.id, null, null)
                        }
                    },
                    primary = true,
                    modifier = Modifier.focusRequester(playFocus),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.resumeTarget?.label ?: "Play",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                // Start over sits right beside Resume — for a part-watched MOVIE and (new) a
                // part-watched EPISODE, where Play resumes mid-way and there was no way to restart it.
                if (state.hasMovieResume || state.hasEpisodeResume) {
                    val rt = state.resumeTarget
                    DetailsActionButton(
                        onClick = {
                            if (state.isTv) onStartOver(MediaType.TV, item.id, rt?.season, rt?.episode)
                            else onStartOver(MediaType.MOVIE, item.id, null, null)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Replay,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Start over", style = MaterialTheme.typography.labelLarge)
                    }
                }

                DetailsActionButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                        tint = if (isFavorite) Brand.Error else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isFavorite) "Favourited" else "Favourite", style = MaterialTheme.typography.labelLarge)
                }

                // TV shows fit their season download beside Play/Favourite (three actions total).
                if (state.isTv) {
                    // Same predicate downloadSeason enqueues with (aired OR unknown air date) — the
                    // aggregate must count exactly what a tap creates or the label lies/freezes.
                    val aired = state.episodes.filter { it.isDownloadable() }
                    val doneCount = aired.count { seasonDownloads[it.episodeNumber]?.isComplete == true }
                    val seasonActive = aired.any {
                        val s = seasonDownloads[it.episodeNumber]?.status
                        s == com.slickstream.core.model.DownloadStatus.QUEUED ||
                            s == com.slickstream.core.model.DownloadStatus.DOWNLOADING
                    }
                    val allDone = aired.isNotEmpty() && doneCount == aired.size
                    val sn = state.selectedSeasonNumber
                    val seasonLabel = when {
                        allDone -> "Downloaded" + (sn?.let { " S$it" } ?: "")
                        seasonActive -> "Downloading $doneCount/${aired.size}"
                        sn != null -> "Download S$sn"
                        else -> "Download season"
                    }
                    DetailsActionButton(onClick = { if (!seasonActive && !allDone) onDownloadSeason() }) {
                        Icon(
                            imageVector = if (allDone) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                            contentDescription = seasonLabel,
                            tint = when {
                                allDone -> Brand.Cyan
                                seasonActive -> Brand.Cyan
                                else -> Color.White
                            },
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(seasonLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Movie watched/download actions get their own compact row so Resume + Start over stay
            // prominent and the action group remains inside the TV safe area.
            if (!state.isTv) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val watched = state.isMovieWatched
                    DetailsActionButton(onClick = { if (watched) onMarkMovieUnwatched() else onMarkMovieWatched() }) {
                        Icon(
                            imageVector = if (watched) Icons.Rounded.CheckCircle else Icons.Outlined.CheckCircle,
                            contentDescription = if (watched) "Mark unwatched" else "Mark watched",
                            tint = if (watched) Brand.Cyan else Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (watched) "Watched" else "Mark watched", style = MaterialTheme.typography.labelLarge)
                    }

                    val done = downloadState?.isComplete == true
                    val downloading = downloadState?.status == com.slickstream.core.model.DownloadStatus.DOWNLOADING ||
                        downloadState?.status == com.slickstream.core.model.DownloadStatus.QUEUED
                    val label = when {
                        done -> "Downloaded"
                        downloading -> "Downloading ${((downloadState?.progress ?: 0f) * 100).toInt()}%"
                        else -> "Download"
                    }
                    DetailsActionButton(onClick = { if (!done && !downloading) onDownloadMovie() }) {
                        Icon(
                            imageVector = if (done) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                            contentDescription = label,
                            tint = if (done) Brand.Cyan else Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Details Play / Favourite / Mark-watched action with strong 10-foot focus feedback (violet fill + 3dp
 * white ring + scale), matching the home hero + nav rail. The plain Material3 Button barely changed on
 * focus. [primary] paints Play violet even at rest so it reads as the main action.
 */
@Composable
private fun DetailsActionButton(
    onClick: () -> Unit,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Brand.Violet else Brand.SurfaceVariant,
            focusedContainerColor = Brand.Violet,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

private fun metaLine(details: com.slickstream.core.model.MediaDetails): String? {
    val runtime = details.runtimeMinutes?.takeIf { it > 0 }?.let { "${it} min" }
    val seasons = details.numberOfSeasons?.takeIf { it > 0 }?.let { "$it season${if (it == 1) "" else "s"}" }
    return runtime ?: seasons
}

@Composable
private fun SeasonSelector(
    seasons: List<Season>,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Seasons",
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 48.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp),
        ) {
            items(seasons, key = { it.seasonNumber }) { season ->
                val isSelected = season.seasonNumber == selected
                val shape = RoundedCornerShape(50)
                Surface(
                    onClick = { onSelect(season.seasonNumber) },
                    shape = ClickableSurfaceDefaults.shape(shape = shape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) Brand.Violet else Brand.SurfaceVariant,
                        focusedContainerColor = Brand.Violet,
                        contentColor = if (isSelected) Color.White else Brand.OnSurface,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                            shape = shape,
                        ),
                    ),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
                ) {
                    Text(
                        text = season.name.ifBlank { "Season ${season.seasonNumber}" },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeList(
    state: DetailsUiState,
    downloads: Map<Int, com.slickstream.core.model.Download>,
    onDownloadEpisode: (Episode) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onToggleWatched: (Episode, watched: Boolean) -> Unit,
) {
    when {
        state.isLoadingEpisodes -> Box(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            contentAlignment = Alignment.Center,
        ) { TvLoading(Modifier.height(48.dp)) }

        state.episodesError != null -> Text(
            text = state.episodesError!!,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.Error,
            modifier = Modifier.padding(start = 48.dp),
        )

        else -> LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
        ) {
            items(
                state.episodes,
                key = { "${it.seasonNumber}-${it.episodeNumber}" },
                contentType = { "episode" },
            ) { ep ->
                EpisodeCard(
                    episode = ep,
                    progress = state.episodeProgress[ep.episodeNumber],
                    download = downloads[ep.episodeNumber],
                    onClick = { onPlayEpisode(ep) },
                    onToggleWatched = { watched -> onToggleWatched(ep, watched) },
                    onDownload = { onDownloadEpisode(ep) },
                )
            }
        }
    }
}

private val MONTH_ABBR =
    arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2024-11-28" -> "Nov 28, 2024". Null/blank/odd input -> null. */
internal fun formatAirDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split("-")
    if (parts.size != 3) return raw
    val month = parts[1].toIntOrNull() ?: return raw
    val day = parts[2].toIntOrNull() ?: return raw
    val mon = MONTH_ABBR.getOrNull(month - 1) ?: return raw
    return "$mon $day, ${parts[0]}"
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    progress: Float?,
    download: com.slickstream.core.model.Download?,
    onClick: () -> Unit,
    onToggleWatched: (watched: Boolean) -> Unit,
    onDownload: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    // Mirror PlaybackProgress.isFinished (>= 0.92f) so a fully-watched episode shows a check.
    val isWatched = (progress ?: 0f) >= 0.92f
    // A future/unreleased episode is shown (info + air date) but can't be played — disabling the
    // Surface makes it non-clickable so the D-pad lands on the last AIRED episode instead. BUT a
    // downloaded episode is always playable (offline), even one with no/future air date.
    val aired = episode.hasAired()
    val playable = aired || download?.isComplete == true
    Column(
        modifier = Modifier.width(300.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
    Surface(
        onClick = onClick,
        enabled = playable,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            focusedContainerColor = Brand.Surface,
            disabledContainerColor = Brand.Surface,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp),
            ) {
                val context = LocalContext.current
                // Hard decode ceiling — a 640x360 still is plenty for this 300dp/168dp card, so
                // Coil doesn't decode full-res stills during D-pad scroll on a weak TV SoC.
                val stillRequest = androidx.compose.runtime.remember(episode.stillUrl) {
                    ImageRequest.Builder(context)
                        .data(episode.stillUrl)
                        .size(640, 360)
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = stillRequest,
                    contentDescription = episode.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Brand.SurfaceVariant),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color(0xCC000000),
                            ),
                        ),
                )
                if (aired) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                } else {
                    // Upcoming episode: an "Airs <date>" pill instead of a play affordance.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xE6000000))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = formatAirDate(episode.airDate)?.let { "Airs $it" } ?: "Coming soon",
                            style = MaterialTheme.typography.labelMedium,
                            color = Brand.Star,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Watched check sits in the top-end corner once the episode is essentially done.
                if (isWatched) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Watched",
                        tint = Brand.Cyan,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(24.dp),
                    )
                }

                // Thin resume bar pinned to the bottom of the still (Violet fill over a
                // translucent track), mirroring TvPosterCard. No bar when progress is null/0.
                progress?.takeIf { it > 0f }?.let { p ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x55000000)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(p.coerceIn(0f, 1f))
                                .fillMaxSize()
                                .background(Brand.Violet),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "E${episode.episodeNumber} · ${episode.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                formatAirDate(episode.airDate)?.let { date ->
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelMedium,
                        color = Brand.Cyan,
                    )
                }
                Text(
                    text = episode.overview.ifBlank { "No description available." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
        // D-pad focusable action pills — SEPARATE focus targets placed BELOW the card (a clickable
        // Surface is a single focus target; the D-pad can't descend into nested clickable children, so
        // pills nested inside the card were unreachable on TV). DOWN from the card focuses the row.
        // Gated on isDownloadable (aired OR unknown date — matches what downloadSeason enqueues):
        // Mark watched + a per-episode Download with live state.
        if (episode.isDownloadable()) {
            val toggleShape = RoundedCornerShape(50)
            Row(
                modifier = Modifier.padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = { onToggleWatched(isWatched) },
                    shape = ClickableSurfaceDefaults.shape(shape = toggleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Brand.SurfaceVariant,
                        focusedContainerColor = Brand.Violet,
                        contentColor = Brand.OnSurface,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                            shape = toggleShape,
                        ),
                    ),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.04f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isWatched) Icons.Rounded.CheckCircle else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (isWatched) Brand.Cyan else Brand.OnSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isWatched) "Watched" else "Mark watched",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }

                val dlStatus = download?.status
                val dlActive = dlStatus == com.slickstream.core.model.DownloadStatus.QUEUED ||
                    dlStatus == com.slickstream.core.model.DownloadStatus.DOWNLOADING
                val dlDone = dlStatus == com.slickstream.core.model.DownloadStatus.COMPLETED
                Surface(
                    onClick = { if (!dlActive && !dlDone) onDownload() },
                    shape = ClickableSurfaceDefaults.shape(shape = toggleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Brand.SurfaceVariant,
                        focusedContainerColor = Brand.Violet,
                        contentColor = Brand.OnSurface,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                            shape = toggleShape,
                        ),
                    ),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.04f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (dlDone) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                            contentDescription = null,
                            tint = when {
                                dlDone -> Color(0xFF22C55E)
                                dlStatus == com.slickstream.core.model.DownloadStatus.FAILED -> Brand.Error
                                dlActive -> Brand.Cyan
                                else -> Brand.OnSurface
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                dlDone -> "Downloaded"
                                dlStatus == com.slickstream.core.model.DownloadStatus.DOWNLOADING ->
                                    "${((download?.progress ?: 0f) * 100).toInt()}%"
                                dlStatus == com.slickstream.core.model.DownloadStatus.QUEUED -> "Queued"
                                dlStatus == com.slickstream.core.model.DownloadStatus.FAILED -> "Retry"
                                else -> "Download"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
