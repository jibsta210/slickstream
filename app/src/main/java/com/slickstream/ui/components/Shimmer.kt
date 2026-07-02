package com.slickstream.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import com.slickstream.ui.theme.Brand

/**
 * A subtle horizontal shimmer used as a placeholder while images / content load.
 * Applied as a background so it can sit underneath an [androidx.compose.foundation.layout.Box]
 * with rounded clipping already in place.
 *
 * The sweep is drawn in the DRAW phase: the animated value is read inside [drawWithCache]'s
 * draw lambda, so each frame is a draw-only invalidation of this node — no recomposition and no
 * per-frame Brush allocation. The old `composed` + `background(Brush.linearGradient(...))`
 * version recomposed EVERY loading card EVERY frame and allocated a fresh brush each time —
 * a whole row of placeholders was a per-frame recomposition storm exactly while the network and
 * eMMC were already saturated (the visible row-load jank on weak TV boxes).
 */
@Composable
fun Modifier.shimmer(active: Boolean = true): Modifier {
    // When inactive, paint a static fill and start NO animation — critical on TV where dozens of
    // cards would otherwise each run a 60fps infinite transition forever, even under loaded art.
    if (!active) return background(Brand.SurfaceVariant)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )
    val base = Brand.SurfaceVariant
    val highlight = Color(0xFF2B2B38)
    return drawWithCache {
        // One static diagonal highlight band; the per-frame motion is a canvas translate.
        val sweep = 350f
        val brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(0f, 0f),
            end = Offset(sweep, sweep),
        )
        onDrawBehind {
            drawRect(base)
            // Animated read INSIDE the draw lambda -> draw-phase-only invalidation.
            val dx = -sweep + progress.value * (size.width + sweep)
            translate(left = dx) {
                drawRect(brush = brush, size = size)
            }
        }
    }
}

/** A static placeholder fill matching the shimmer base tone (no animation). */
@Composable
fun placeholderBrush(): Brush = Brush.verticalGradient(
    colors = listOf(Brand.SurfaceVariant, Brand.Surface),
)
