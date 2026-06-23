package com.slickstream.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.slickstream.core.model.MediaItem
import com.slickstream.ui.components.RatingBadge
import com.slickstream.ui.components.shimmer
import com.slickstream.ui.theme.Brand

/**
 * A focus-scaling poster tile for the TV catalog rows.
 *
 * Uses an `androidx.tv.material3.Surface` so D-pad focus + center/Enter click are handled
 * natively. On focus the tile scales up, gains a violet brand glow + ring, and brightens its
 * title. Optional [progress] (0f..1f) paints a thin resume bar across the bottom for
 * "Continue Watching".
 *
 * @param wide render a landscape (backdrop) tile instead of a portrait poster — used for the
 *   continue-watching row.
 */
@Composable
fun TvPosterCard(
    item: MediaItem,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 132.dp,
    wide: Boolean = false,
    progress: Float? = null,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val cardWidth = if (wide) width * 1.75f else width
    val cardHeight = if (wide) width * 1.0f else width * 1.5f
    val image = if (wide) (item.backdropUrl ?: item.posterUrl) else (item.posterUrl ?: item.backdropUrl)
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier.width(cardWidth),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            onClick = { onClick(item) },
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            scale = ClickableSurfaceDefaults.scale(
                scale = 1f,
                focusedScale = 1.1f,
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Brand.Surface,
                focusedContainerColor = Brand.Surface,
                pressedContainerColor = Brand.Surface,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet),
                    shape = shape,
                ),
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(elevationColor = Brand.Violet, elevation = 16.dp),
            ),
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (image != null) {
                    var loaded by remember(image) { mutableStateOf(false) }
                    AsyncImage(
                        model = image,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        onState = { st -> loaded = st is coil.compose.AsyncImagePainter.State.Success },
                        modifier = Modifier.fillMaxSize().shimmer(active = !loaded),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().shimmer(active = true),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = Brand.OnSurfaceDim,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                // Bottom scrim only when a badge / resume bar actually sits on it (saves overdraw).
                if (item.voteAverage > 0.0 || (progress != null && progress > 0f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.5f to Color.Transparent,
                                    1f to Color(0xCC000000),
                                ),
                            ),
                    )
                }

                if (item.voteAverage > 0.0) {
                    RatingBadge(
                        vote = item.voteAverage,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    )
                }

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
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) Brand.OnSurface else Brand.OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 8.dp)
                .width(cardWidth),
        )
        item.year?.let { yr ->
            Text(
                text = yr,
                style = MaterialTheme.typography.labelMedium,
                color = Brand.OnSurfaceDim,
                maxLines = 1,
            )
        }
    }
}
