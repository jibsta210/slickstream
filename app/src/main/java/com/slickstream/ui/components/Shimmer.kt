package com.slickstream.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.slickstream.ui.theme.Brand

/**
 * A subtle horizontal shimmer used as a placeholder while images / content load.
 * Applied as a background so it can sit underneath an [androidx.compose.foundation.layout.Box]
 * with rounded clipping already in place.
 */
fun Modifier.shimmer(active: Boolean = true): Modifier = composed {
    // When inactive, paint a static fill and start NO animation — critical on TV where dozens of
    // cards would otherwise each run a 60fps infinite transition forever, even under loaded art.
    if (!active) return@composed background(Brand.SurfaceVariant)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -700f,
        targetValue = 700f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-translate",
    )
    val base = Brand.SurfaceVariant
    val highlight = Color(0xFF2B2B38)
    background(
        brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(translate - 350f, 0f),
            end = Offset(translate, 350f),
        ),
    )
}

/** A static placeholder fill matching the shimmer base tone (no animation). */
@Composable
fun placeholderBrush(): Brush = Brush.verticalGradient(
    colors = listOf(Brand.SurfaceVariant, Brand.Surface),
)
