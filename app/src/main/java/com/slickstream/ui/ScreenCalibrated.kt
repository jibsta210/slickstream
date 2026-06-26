package com.slickstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Applies the user's screen calibration (TV overscan/underscan fit) to the WHOLE app: a uniform scale
 * (grow past 1f to fill an under-scanning panel, shrink below 1f when a panel crops the edges) plus a
 * positional nudge to centre it. Done via LAYOUT sizing (requiredSize), not a graphics transform, so it
 * also resizes the player's video SurfaceView (which ignores Compose graphicsLayer transforms).
 *
 * No wrapper at all when calibration is the identity — so uncalibrated users pay nothing and nothing
 * changes for them.
 */
@Composable
fun ScreenCalibrated(
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    content: @Composable () -> Unit,
) {
    if (scale == 1f && offsetX == 0f && offsetY == 0f) {
        content()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .requiredSize(maxWidth * scale, maxHeight * scale)
                .align(Alignment.Center)
                .offset(x = offsetX.dp, y = offsetY.dp),
        ) {
            content()
        }
    }
}
