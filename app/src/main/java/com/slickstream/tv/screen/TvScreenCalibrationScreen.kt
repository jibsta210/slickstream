package com.slickstream.tv.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.feature.settings.SettingsViewModel
import com.slickstream.ui.theme.Brand
import kotlin.math.roundToInt

/**
 * TV screen calibration: a cyan full-bleed border shows where the app's edges currently land; the user
 * grows/shrinks and nudges it until the border sits exactly on their panel's visible edges. Saved live
 * (the whole app — including this screen — re-fits as it's adjusted), so the border moves under the
 * user's control. Fixes a panel that under-scans SlickStream where other apps happen to fill.
 */
@Composable
fun TvScreenCalibrationScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var scale by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        if (!initialized) {
            scale = settings.screenScale; offX = settings.screenOffsetX; offY = settings.screenOffsetY
            initialized = true
        }
    }
    var sel by remember { mutableStateOf(0) } // 0 size · 1 horizontal · 2 vertical · 3 reset · 4 done
    val focus = remember { FocusRequester() }
    // Retry focus past layout — a single requestFocus() fired before the focusable is attached silently
    // fails on a real TV, leaving the D-pad uncaptured so arrow presses escaped to navigation.
    LaunchedEffect(Unit) {
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // Live preview is instant + in-memory (no disk write per keypress); the value is persisted ONCE,
    // when the user leaves the screen (Done or Back) — so adjustments stick without hammering DataStore.
    fun preview() = viewModel.setLiveCalibration(scale, offX, offY)
    fun finish() {
        viewModel.commitScreenCalibration(scale, offX, offY)
        onBack()
    }
    fun adjust(dir: Int) {
        when (sel) {
            0 -> scale = (scale + dir * 0.005f).coerceIn(0.80f, 1.20f)
            // Horizontal/Vertical move ONE PIXEL per press — fine enough to perfectly centre against an
            // asymmetric-overscan panel (the value is raw pixels; see ScreenCalibrated).
            1 -> offX = (offX + dir * 1f).coerceIn(-200f, 200f)
            2 -> offY = (offY + dir * 1f).coerceIn(-200f, 200f)
        }
        preview()
    }

    // Diagnostic: what insets does the panel actually report? If these are all 0 and a black border
    // still shows, it's true hardware overscan (use the sliders). If a side is non-zero / left != right,
    // it's a window inset the app should consume — tells us which problem we're really looking at.
    val density = LocalDensity.current
    val ld = LocalLayoutDirection.current
    val bars = WindowInsets.systemBars
    val cut = WindowInsets.displayCutout
    val insetReadout = "panel report — bars L${bars.getLeft(density, ld)} T${bars.getTop(density)} " +
        "R${bars.getRight(density, ld)} B${bars.getBottom(density)} · " +
        "cutout L${cut.getLeft(density, ld)} R${cut.getRight(density, ld)}"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brand.Background)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { sel = (sel + 4) % 5; true }
                    Key.DirectionDown -> { sel = (sel + 1) % 5; true }
                    Key.DirectionLeft -> { if (sel <= 2) adjust(-1); true }
                    Key.DirectionRight -> { if (sel <= 2) adjust(+1); true }
                    Key.DirectionCenter, Key.Enter -> {
                        when (sel) {
                            3 -> { scale = 1f; offX = 0f; offY = 0f; preview() }
                            4 -> finish()
                        }
                        true
                    }
                    Key.Back -> { finish(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Full-bleed cyan border = the calibrated app's edges. Line it up with your TV's visible edges.
        Box(Modifier.fillMaxSize().border(3.dp, Brand.Cyan))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 36.dp, vertical = 28.dp),
        ) {
            Text("Screen calibration", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                "Grow/shrink and shift the app until the cyan border sits exactly on the edges of your TV.",
                color = Brand.OnSurfaceDim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(360.dp),
            )
            Spacer(Modifier.height(6.dp))
            CalibRow("Size", "${(scale * 100).roundToInt()}%", sel == 0)
            CalibRow("Horizontal", "${offX.roundToInt()} px", sel == 1)
            CalibRow("Vertical", "${offY.roundToInt()} px", sel == 2)
            CalibRow("Reset to default", "", sel == 3)
            CalibRow("Done", "", sel == 4)
            Spacer(Modifier.height(6.dp))
            Text(
                "↑↓ select   ·   ←→ adjust   ·   OK to reset / finish",
                color = Brand.OnSurfaceDim,
                fontSize = 12.sp,
            )
            Text(
                insetReadout,
                color = Brand.OnSurfaceDim.copy(alpha = 0.6f),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun CalibRow(label: String, value: String, selected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Brand.Violet else Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                if (selected) "◀  $value  ▶" else value,
                color = if (selected) Color.White else Brand.OnSurfaceDim,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
    }
}
