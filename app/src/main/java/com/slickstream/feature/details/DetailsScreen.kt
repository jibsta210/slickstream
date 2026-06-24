package com.slickstream.feature.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.slickstream.core.model.CastMember
import com.slickstream.core.model.Episode
import com.slickstream.core.model.Genre
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.Season
import com.slickstream.ui.components.ErrorRetry
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.components.MediaRow
import com.slickstream.ui.components.RatingBadge
import com.slickstream.ui.components.SectionHeader
import com.slickstream.ui.theme.Brand

/** Signature the navigation host wires up to launch the player. */
typealias OnPlay = (mediaType: MediaType, mediaId: Int, season: Int?, episode: Int?) -> Unit

@Composable
fun DetailsScreen(
    onPlay: OnPlay,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().background(Brand.Background)) {
        when {
            state.isLoading -> LoadingState(Modifier.fillMaxSize())

            state.details == null -> ErrorRetry(
                message = state.errorMessage ?: "Couldn't load details.",
                onRetry = viewModel::load,
                modifier = Modifier.fillMaxSize(),
            )

            else -> DetailsContent(
                details = state.details!!,
                state = state,
                isFavorite = isFavorite,
                onPlay = onPlay,
                onToggleFavorite = viewModel::toggleFavorite,
                onSelectSeason = viewModel::selectSeason,
                onMediaClick = onMediaClick,
            )
        }

        // Floating back button — always visible above the backdrop.
        BackButton(
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )
    }
}

@Composable
private fun DetailsContent(
    details: MediaDetails,
    state: DetailsUiState,
    isFavorite: Boolean,
    onPlay: OnPlay,
    onToggleFavorite: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
) {
    val item = details.item

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // --- Collapsing backdrop header with gradient scrim ---
        item("backdrop") {
            BackdropHeader(details = details)
        }

        // --- Title block + meta row ---
        item("meta") {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Brand.OnSurface,
                )
                if (!details.tagline.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = details.tagline!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.Cyan,
                    )
                }
                Spacer(Modifier.height(10.dp))
                MetaRow(details = details)
            }
        }

        // --- Actions: Play + favourite heart ---
        item("actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isTv = item.mediaType == MediaType.TV
                val firstSeason = state.selectedSeasonNumber ?: state.seasons.firstOrNull()?.seasonNumber
                PlayButton(
                    label = if (isTv) "Play S${firstSeason ?: 1}" else "Play",
                    onClick = {
                        if (isTv) {
                            onPlay(item.mediaType, item.id, firstSeason ?: 1, 1)
                        } else {
                            onPlay(item.mediaType, item.id, null, null)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                FavoriteHeart(
                    isFavorite = isFavorite,
                    onToggle = onToggleFavorite,
                )
            }
        }

        // --- Overview ---
        if (item.overview.isNotBlank()) {
            item("overview") {
                Text(
                    text = item.overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.OnSurfaceDim,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // --- Genre chips ---
        if (details.genres.isNotEmpty()) {
            item("genres") {
                GenreChips(
                    genres = details.genres,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        // --- Cast row ---
        if (details.cast.isNotEmpty()) {
            item("cast-header") {
                SectionHeader(
                    title = "Cast",
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            item("cast-row") {
                CastRow(cast = details.cast)
            }
        }

        // --- Seasons + episodes (TV only) ---
        if (item.mediaType == MediaType.TV && state.seasons.isNotEmpty()) {
            item("seasons-header") {
                SectionHeader(
                    title = "Episodes",
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            item("season-selector") {
                SeasonSelector(
                    seasons = state.seasons,
                    selected = state.selectedSeasonNumber,
                    onSelect = onSelectSeason,
                )
            }

            when {
                state.isLoadingEpisodes -> item("episodes-loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    ) { LoadingState(Modifier.fillMaxSize()) }
                }

                state.episodesError != null -> item("episodes-error") {
                    ErrorRetry(
                        message = state.episodesError,
                        onRetry = { state.selectedSeasonNumber?.let(onSelectSeason) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    )
                }

                else -> items(
                    items = state.episodes,
                    key = { "ep-${it.seasonNumber}-${it.episodeNumber}" },
                ) { episode ->
                    EpisodeRow(
                        episode = episode,
                        onPlay = {
                            onPlay(
                                item.mediaType,
                                item.id,
                                episode.seasonNumber,
                                episode.episodeNumber,
                            )
                        },
                    )
                }
            }
        }

        // --- More like this ---
        if (state.similar.isNotEmpty()) {
            item("similar") {
                MediaRow(
                    title = "More like this",
                    items = state.similar,
                    onItemClick = onMediaClick,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun BackdropHeader(details: MediaDetails) {
    val item = details.item
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.backdropUrl ?: item.posterUrl)
                .size(1080, 675)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(Brand.SurfaceVariant),
        )
        // Bottom-to-top gradient scrim so the title block reads cleanly over the image.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.55f to Brand.Background.copy(alpha = 0.35f),
                            1.0f to Brand.Background,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun MetaRow(details: MediaDetails) {
    val item = details.item
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RatingBadge(vote = item.voteAverage)
        item.year?.let { MetaText(it) }
        runtimeLabel(details)?.let { MetaText(it) }
        if (item.mediaType == MediaType.TV) {
            details.numberOfSeasons?.let { count ->
                MetaText(if (count == 1) "1 Season" else "$count Seasons")
            }
        }
        details.status?.takeIf { it.isNotBlank() }?.let { MetaText(it) }
    }
}

private fun runtimeLabel(details: MediaDetails): String? {
    val minutes = details.runtimeMinutes ?: return null
    if (minutes <= 0) return null
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Brand.OnSurfaceDim,
    )
}

@Composable
private fun PlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.Violet,
            contentColor = Color.White,
        ),
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FavoriteHeart(
    isFavorite: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = Brand.SurfaceVariant,
        modifier = Modifier.size(50.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                tint = if (isFavorite) Brand.Error else Brand.OnSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun GenreChips(
    genres: List<Genre>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(genres, key = { it.id }) { genre ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(genre.name) },
                shape = RoundedCornerShape(10.dp),
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = Brand.SurfaceVariant,
                    disabledLabelColor = Brand.OnSurface,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun CastRow(cast: List<CastMember>) {
    val context = LocalContext.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(cast, key = { it.id }) { member ->
            Column(
                modifier = Modifier.width(76.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(member.profileUrl)
                        .size(160, 160)
                        .crossfade(true)
                        .build(),
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Brand.SurfaceVariant),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (member.character.isNotBlank()) {
                    Text(
                        text = member.character,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.OnSurfaceDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonSelector(
    seasons: List<Season>,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasons, key = { it.seasonNumber }) { season ->
            val isSelected = season.seasonNumber == selected
            Surface(
                onClick = { onSelect(season.seasonNumber) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) Brand.Violet else Brand.SurfaceVariant,
                contentColor = if (isSelected) Color.White else Brand.OnSurface,
            ) {
                Text(
                    text = season.name.ifBlank { "Season ${season.seasonNumber}" },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    onPlay: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(episode.stillUrl)
                    .size(360, 202)
                    .crossfade(true)
                    .build(),
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brand.SurfaceVariant),
            )
            // Play affordance overlaid on the still.
            Box(
                modifier = Modifier
                    .matchPlayOverlaySize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    onClick = onPlay,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play episode ${episode.episodeNumber}",
                        tint = Color.White,
                        modifier = Modifier.padding(6.dp).size(22.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${episode.episodeNumber}. ${episode.name}",
                style = MaterialTheme.typography.titleMedium,
                color = Brand.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.runtimeMinutes?.takeIf { it > 0 }?.let { mins ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${mins}m",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurfaceDim,
                )
            }
            if (episode.overview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Matches the size of the episode still so the play overlay covers exactly it. */
private fun Modifier.matchPlayOverlaySize(): Modifier =
    this
        .width(132.dp)
        .aspectRatio(16f / 9f)

@Composable
private fun BackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onBack,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
