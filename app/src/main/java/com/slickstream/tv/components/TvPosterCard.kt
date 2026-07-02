package com.slickstream.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.slickstream.core.common.Img
import com.slickstream.core.model.MediaItem
import com.slickstream.ui.components.RatingBadge
import com.slickstream.ui.components.shimmer
import com.slickstream.ui.theme.Brand

/**
 * A focus-scaling poster tile for the TV catalog rows.
 *
 * Uses an `androidx.tv.material3.Surface` so D-pad focus + center/Enter click are handled
 * natively. On focus the tile scales up, gains a violet border + white ring, and brightens its
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
    fillCell: Boolean = false,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // Kept as State (not delegated): the ring reads it in the DRAW phase and the title in its own
    // tiny composable, so a D-pad focus change no longer recomposes this whole card (2x per
    // keypress — leave + enter — in the same frame the Surface starts its scale/border animation).
    val focusedState = interaction.collectIsFocusedAsState()

    val cardWidth = if (wide) width * 1.75f else width
    val cardHeight = if (wide) width * 1.0f else width * 1.5f
    // Wide tiles crop a backdrop: fetch the w780 variant, not the hero-sized w1280 (see Img).
    val image = if (wide) {
        (Img.cardBackdrop(item.backdropUrl) ?: item.posterUrl)
    } else {
        (item.posterUrl ?: item.backdropUrl)
    }
    val shape = RoundedCornerShape(14.dp)

    // In a grid the tile fills its cell (the caller supplies fillMaxWidth) and the art keeps the
    // poster/backdrop aspect ratio; in a row the tile keeps its fixed width/height.
    Column(
        modifier = if (fillCell) modifier else modifier.width(cardWidth),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            onClick = { onClick(item) },
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            scale = ClickableSurfaceDefaults.scale(
                scale = 1f,
                focusedScale = 1.06f,
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
            // No focus Glow: a 16dp animated render-node shadow is one of the most expensive
            // single effects on Mali-class TV GPUs, and it ran on every D-pad step. The 3dp violet
            // border + white ring below keep focus unmistakable.
            modifier = if (fillCell) {
                Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f)
            } else {
                Modifier.width(cardWidth).height(cardHeight)
            },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Focus ring ON TOP of the (edge-to-edge cropped) art so it's always visible.
                    // Drawn in the draw phase (state read inside the lambda), so focus changes
                    // invalidate only this node's draw — no recomposition, no extra overlay Box.
                    .drawWithContent {
                        drawContent()
                        if (focusedState.value) {
                            val stroke = 3.dp.toPx()
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                cornerRadius = CornerRadius(14.dp.toPx()),
                                style = Stroke(width = stroke),
                            )
                        }
                    },
            ) {
                if (image != null) {
                    var loaded by remember(image) { mutableStateOf(false) }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    // Hard decode ceiling — a ~360px poster is plenty for this tile, so Coil
                    // doesn't decode full-res TMDB bitmaps during D-pad scroll (frame drops + GC
                    // on a weak TV SoC). Hoisted behind remember so the request isn't rebuilt on
                    // every focus-driven recomposition.
                    // No crossfade: the shimmer -> image swap already reads as a transition, and a
                    // row populating meant a dozen simultaneous CrossfadePainter composites on a
                    // fill-rate-bound GPU.
                    val request = remember(image, wide) {
                        coil.request.ImageRequest.Builder(context)
                            .data(image)
                            .size(if (wide) 480 else 360, if (wide) 270 else 540)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        onState = { st -> loaded = st is coil.compose.AsyncImagePainter.State.Success },
                        modifier = Modifier.fillMaxSize().shimmer(active = !loaded),
                    )
                } else {
                    // No artwork will EVER arrive for this tile — static fill, not shimmer, or the
                    // "loading" animation runs at 60fps forever for every artless item on screen.
                    Box(
                        modifier = Modifier.fillMaxSize().background(Brand.SurfaceVariant),
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
                            .align(Alignment.TopEnd)
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

        // Own composable so the focus-driven color change recomposes ONLY this Text, not the card.
        CardTitle(
            title = item.title,
            focused = focusedState,
            modifier = Modifier
                .padding(top = 8.dp)
                .then(if (fillCell) Modifier.fillMaxWidth() else Modifier.width(cardWidth)),
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

@Composable
private fun CardTitle(title: String, focused: State<Boolean>, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = if (focused.value) Brand.OnSurface else Brand.OnSurfaceDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
