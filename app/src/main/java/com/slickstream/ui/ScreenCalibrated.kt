package com.slickstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Applies the user's screen calibration (TV overscan/underscan fit) to the WHOLE app: a uniform scale
 * (grow past 1f to fill an under-scanning panel, shrink below 1f when a panel crops the edges) plus a
 * positional nudge to centre it. Done via LAYOUT sizing, not a graphics transform, so it also resizes
 * the player's video SurfaceView (which ignores Compose graphicsLayer transforms but honours layout
 * bounds).
 *
 * The transform is applied through ONE always-present [Modifier.layout] — never a conditional wrapper.
 * That matters: a structural "wrap only when calibrated" branch gave `content` (the entire app +
 * NavHost) a brand-new identity the instant the user first nudged a slider, so Compose tore down and
 * rebuilt the whole app mid-calibration — focus was destroyed and the launch profile-gate re-fired
 * straight into the profile picker. With a single stable layout node, changing scale/offset only
 * re-measures and re-places; nothing is recreated, focus survives, navigation is untouched. At scale 1
 * / offset 0 it is an exact no-op (child measured at full size, placed at the origin).
 */
@Composable
fun ScreenCalibrated(
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    content: @Composable () -> Unit,
) {
    // Black fills whatever the scaled-down child doesn't cover (and shows through on an offset).
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val w = (constraints.maxWidth * scale).roundToInt().coerceAtLeast(1)
                    val h = (constraints.maxHeight * scale).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    val dx = offsetX.dp.roundToPx()
                    val dy = offsetY.dp.roundToPx()
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            x = (constraints.maxWidth - w) / 2 + dx,
                            y = (constraints.maxHeight - h) / 2 + dy,
                        )
                    }
                },
        ) {
            content()
        }
    }
}
