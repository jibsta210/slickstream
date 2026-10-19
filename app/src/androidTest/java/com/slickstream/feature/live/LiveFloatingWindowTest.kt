package com.slickstream.feature.live

import android.graphics.Rect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import com.slickstream.feature.player.LocalPipController
import com.slickstream.feature.player.PipController
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiveFloatingWindowTest {
    @get:Rule val compose = createComposeRule()
    private class Controller(override val isSupported: Boolean = true) : PipController {
        override val isInPip = mutableStateOf(false)
        var enabled = false
        var hint: Rect? = null
        var entries = 0
        override fun update(enabled: Boolean, aspect: Float, sourceHint: Rect?) {
            this.enabled = enabled
            hint = sourceHint
        }
        override fun enter() { if (enabled) { entries++; isInPip.value = true } }
    }
    private class Tile(override val id: Int) : TileSession {
        override val title = "Game $id"
        override val uiState = MutableStateFlow<TileUiState>(TileUiState.Playing)
        override val player = MutableStateFlow<Player?>(null)
        override fun setAudible(value: Boolean) = Unit
        override fun setVideoEnabled(value: Boolean) = Unit
        override fun setVideoCap(cap: LiveMultiView.VideoCap?) = Unit
        override fun retry() = Unit
        override fun release() = Unit
    }

    @Test fun gridFloatsWithoutAddControlsAndRestoresAllTitles() {
        val controller = Controller()
        val tiles = (1..3).map(::Tile)
        val shown = mutableStateOf(true)
        val expanded = mutableStateOf<Int?>(null)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPipController provides controller) {
                    if (shown.value) LiveFloatingWindow(tiles, expanded.value, true, Modifier.fillMaxSize()) {
                        GridLayout(tiles, 2, !controller.isInPip.value, { FocusRequester() }, true, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Add").assertIsDisplayed()
        compose.onNodeWithText("Floating video").performClick()
        compose.onNodeWithText("Add").assertDoesNotExist()
        compose.onNodeWithText("Floating video").assertDoesNotExist()
        compose.onNodeWithText("Game 1").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(controller.enabled)
            assertEquals(1, controller.entries)
            assertFalse(controller.hint!!.isEmpty)
            controller.isInPip.value = false
        }
        for (tile in tiles) compose.onNodeWithText(tile.title).assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsDisplayed()
        // A failed expanded tile must not arm floating merely because a hidden tile still plays.
        compose.runOnIdle { tiles[0].uiState.value = TileUiState.Error("Failed"); expanded.value = 1 }
        compose.runOnIdle { assertFalse(controller.enabled); expanded.value = null }
        compose.runOnIdle { assertTrue(controller.enabled); shown.value = false }
        compose.runOnIdle { assertFalse(controller.enabled); assertNull(controller.hint) }
    }

    @Test fun unsupportedDevicesHaveNoFloatingAction() {
        val controller = Controller(false)
        compose.setContent {
            CompositionLocalProvider(LocalPipController provides controller) {
                LiveFloatingWindow(listOf(Tile(1)), null, true, Modifier.fillMaxSize()) {}
            }
        }
        compose.onNodeWithText("Floating video").assertDoesNotExist()
    }
}
