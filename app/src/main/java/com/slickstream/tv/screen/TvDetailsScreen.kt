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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.slickstream.core.model.Episode
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
    onMediaClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()

    when {
        state.isLoading && state.details == null -> TvLoading(modifier)
        state.errorMessage != null && state.details == null ->
            TvErrorRetry(message = state.errorMessage!!, onRetry = viewModel::load, modifier = modifier)
        state.details != null -> DetailsContent(
            state = state,
            isFavorite = isFavorite,
            onPlay = onPlay,
            onToggleFavorite = viewModel::toggleFavorite,
            onSelectSeason = viewModel::selectSeason,
            onMediaClick = onMediaClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun DetailsContent(
    state: DetailsUiState,
    isFavorite: Boolean,
    onPlay: (MediaType, Int, Int?, Int?) -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val details = state.details ?: return
    val item = details.item

    Box(modifier = modifier.fillMaxSize().background(Brand.Background)) {
        // Backdrop fills the top of the screen, fading into the background.
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.backdropUrl ?: item.posterUrl)
                .size(1280, 720) // hard decode ceiling so a 4K panel doesn't decode at 2x
                .scale(Scale.FILL)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(560.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(560.dp)
                .background(
                    Brush.verticalGradient(
                        0.3f to Color.Transparent,
                        1f to Brand.Background,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xCC0B0B0F),
                        0.6f to Color.Transparent,
                    ),
                ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 220.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item(key = "hero") {
                HeroBlock(
                    state = state,
                    isFavorite = isFavorite,
                    onPlay = onPlay,
                    onToggleFavorite = onToggleFavorite,
                )
            }

            if (state.isTv && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    SeasonSelector(
                        seasons = state.seasons,
                        selected = state.selectedSeasonNumber,
                        onSelect = onSelectSeason,
                    )
                }
                item(key = "episodes") {
                    EpisodeList(
                        state = state,
                        onPlayEpisode = { ep ->
                            onPlay(MediaType.TV, item.id, ep.seasonNumber, ep.episodeNumber)
                        },
                    )
                }
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

@Composable
private fun HeroBlock(
    state: DetailsUiState,
    isFavorite: Boolean,
    onPlay: (MediaType, Int, Int?, Int?) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val details = state.details ?: return
    val item = details.item

    // Details hides the rail — land focus on Play the moment it resolves (and on details->details
    // navigation) so the first D-pad press isn't swallowed.
    val playFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(item.id) {
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.62f)
            .padding(start = 48.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        details.tagline?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = Brand.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
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
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                onClick = {
                    if (state.isTv) {
                        // Resume the selected season's first episode (or S1E1) for shows.
                        val season = state.selectedSeasonNumber ?: state.seasons.firstOrNull()?.seasonNumber
                        val episode = state.episodes.firstOrNull()?.episodeNumber ?: 1
                        onPlay(MediaType.TV, item.id, season, episode)
                    } else {
                        onPlay(MediaType.MOVIE, item.id, null, null)
                    }
                },
                modifier = Modifier.focusRequester(playFocus),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.isTv) "Play S1E1" else "Play", style = MaterialTheme.typography.labelLarge)
            }

            Button(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                    tint = if (isFavorite) Brand.Error else Color.White,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isFavorite) "Favourited" else "Favourite", style = MaterialTheme.typography.labelLarge)
            }
        }
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
            modifier = Modifier.padding(start = 48.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp),
        ) {
            items(seasons, key = { it.seasonNumber }) { season ->
                val isSelected = season.seasonNumber == selected
                val shape = RoundedCornerShape(12.dp)
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
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
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
    onPlayEpisode: (Episode) -> Unit,
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
                    onClick = { onPlayEpisode(ep) },
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
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    // Mirror PlaybackProgress.isFinished (>= 0.92f) so a fully-watched episode shows a check.
    val isWatched = (progress ?: 0f) >= 0.92f
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            focusedContainerColor = Brand.Surface,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
        modifier = Modifier.width(300.dp),
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
}
