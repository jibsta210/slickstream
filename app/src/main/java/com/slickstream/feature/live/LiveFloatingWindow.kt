package com.slickstream.feature.live

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.feature.player.LocalPipController
import com.slickstream.feature.player.rememberIsInPipMode

/** The same Android floating window used by movies, with the current multiview kept intact. */
@Composable
internal fun LiveFloatingWindow(
    sessions: List<TileSession>,
    expandedId: Int?,
    showControls: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val controller = LocalPipController.current
    val floating = rememberIsInPipMode()
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val visibleSessions = sessions.filter { expandedId == null || it.id == expandedId }
    val states = visibleSessions.map { it.uiState.collectAsStateWithLifecycle().value }
    val enabled = states.any { it == TileUiState.Playing || it is TileUiState.Recovering }

    LaunchedEffect(controller, enabled, bounds) {
        controller?.update(enabled, 16f / 9f, bounds)
    }
    DisposableEffect(controller) {
        onDispose { controller?.update(false, 16f / 9f, null) }
    }
    Box(modifier.onGloballyPositioned {
        // Retain the full-size source rectangle throughout the shrink/expand animation.
        if (!floating) {
            val rect = it.boundsInWindow()
            bounds = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }) {
        content()
        if (controller?.isSupported == true && enabled && showControls && !floating) {
            FilledTonalButton(
                onClick = { controller.enter() },
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            ) { Text("Floating video") }
        }
    }
}
