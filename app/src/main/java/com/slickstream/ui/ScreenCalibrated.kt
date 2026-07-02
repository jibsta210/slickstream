package com.slickstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
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
    // Black fills whatever the scaled-down child doesn't cover (and shows through on an offset) —
    // but ONLY when a calibration is actually active. At identity the child covers every pixel, so
    // the fill was a pure extra full-screen draw pass per frame on fill-rate-bound TV GPUs. This
    // toggles just the MODIFIER (draw invalidation); the Box node itself stays unconditional, so
    // the no-structural-wrapper rule in the kdoc above is preserved.
    val identity = scale == 1f && offsetX == 0f && offsetY == 0f
    Box(
        Modifier
            .fillMaxSize()
            .then(if (identity) Modifier else Modifier.background(Color.Black)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val w = (constraints.maxWidth * scale).roundToInt().coerceAtLeast(1)
                    val h = (constraints.maxHeight * scale).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    // offsetX/offsetY are RAW PIXELS (not dp) so each calibration step shifts the picture
                    // by exactly one panel pixel — needed to centre against an asymmetric-overscan panel.
                    val dx = offsetX.roundToInt()
                    val dy = offsetY.roundToInt()
                    // Float division (not Int /2, which truncates toward zero and biased the picture
                    // ~0.5px right when scaled up) so the scale is exactly symmetric about centre.
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            x = ((constraints.maxWidth - w).toFloat() / 2f).roundToInt() + dx,
                            y = ((constraints.maxHeight - h).toFloat() / 2f).roundToInt() + dy,
                        )
                    }
                },
        ) {
            content()
        }
    }
}
