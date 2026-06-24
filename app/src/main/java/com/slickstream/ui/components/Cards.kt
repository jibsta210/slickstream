package com.slickstream.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.ui.theme.Brand

private val PosterShape = RoundedCornerShape(12.dp)
private val BackdropShape = RoundedCornerShape(14.dp)

/**
 * Portrait catalog card (2:3). ~132dp wide. Shows poster art with a shimmer placeholder,
 * a rating badge in the top-right, a fallback title/icon when no art exists, and an
 * optional resume-progress bar pinned to the bottom edge.
 */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(),
        label = "poster-scale",
    )

    Box(
        modifier = modifier
            .width(132.dp)
            .scale(scale)
            .clip(PosterShape)
            .background(Brand.Surface)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick(item) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            MediaImage(
                url = item.posterUrl,
                contentDescription = item.title,
                fallbackTitle = item.title,
                mediaType = item.mediaType,
                wide = false,
                modifier = Modifier.fillMaxSize(),
            )

            // Bottom scrim so a progress bar / future captions stay legible over art.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.7f to Color.Transparent,
                            1f to Color(0xAA000000),
                        ),
                    ),
            )

            if (item.voteAverage > 0.0) {
                RatingBadge(
                    vote = item.voteAverage,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }

            progress?.let { p ->
                ProgressStrip(
                    progress = p,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Landscape card (16:9). ~232dp wide. Backdrop art with a title overlay on a bottom
 * gradient scrim, a rating badge, and an optional resume-progress bar.
 */
@Composable
fun BackdropCard(
    item: MediaItem,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(),
        label = "backdrop-scale",
    )

    Box(
        modifier = modifier
            .width(232.dp)
            .scale(scale)
            .clip(BackdropShape)
            .background(Brand.Surface)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick(item) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            MediaImage(
                url = item.backdropUrl ?: item.posterUrl,
                contentDescription = item.title,
                fallbackTitle = item.title,
                mediaType = item.mediaType,
                wide = true,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color(0xE6000000),
                        ),
                    ),
            )

            if (item.voteAverage > 0.0) {
                RatingBadge(
                    vote = item.voteAverage,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )

            progress?.let { p ->
                ProgressStrip(
                    progress = p,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Shared image element: a Coil async-image painter with a shimmer while loading and a
 * branded icon+title fallback on error / null url.
 */
@Composable
private fun MediaImage(
    url: String?,
    contentDescription: String,
    fallbackTitle: String,
    mediaType: MediaType,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(url)
            // Cap the decode resolution to roughly the tile's footprint so we don't
            // decode full-res TMDB bitmaps for small cards (the source of scroll jank).
            .apply {
                if (wide) size(480, 270) else size(360, 540)
            }
            .crossfade(true)
            .build(),
    )
    // painter.state is backed by a MutableState, so reading it here is recomposition-safe.
    val state = painter.state

    Box(modifier = modifier) {
        when (state) {
            is AsyncImagePainter.State.Loading -> {
                Box(modifier = Modifier.fillMaxSize().shimmer())
            }
            is AsyncImagePainter.State.Error,
            is AsyncImagePainter.State.Empty -> {
                MediaFallback(
                    title = fallbackTitle,
                    mediaType = mediaType,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> Unit
        }
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (state is AsyncImagePainter.State.Success) 1f else 0f),
        )
    }
}

@Composable
private fun MediaFallback(
    title: String,
    mediaType: MediaType,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(Brand.SurfaceVariant, Brand.Surface)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = if (mediaType == MediaType.TV) Icons.Rounded.Tv else Icons.Rounded.Movie,
                contentDescription = null,
                tint = Brand.OnSurfaceDim,
                modifier = Modifier.size(34.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.OnSurfaceDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Thin resume bar (violet fill on a dim track) for in-progress playback. */
@Composable
private fun ProgressStrip(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(3.dp)
            .background(Color(0x66000000)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(Brand.Violet),
        )
    }
}
