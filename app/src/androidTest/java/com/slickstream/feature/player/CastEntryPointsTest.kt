package com.slickstream.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.slickstream.cast.CastConnectionState
import com.slickstream.cast.CastDestinationControl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CastEntryPointsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun canChooseTvBeforeOpeningVideoAndSeeConnectionWhileBrowsing() {
        val state = mutableStateOf(CastConnectionState())
        var openedChooser = false
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    CastDestinationControl(state.value, expanded = true) { openedChooser = true }
                    Text("Choose a movie")
                }
            }
        }
        compose.onNodeWithText("Watch on TV").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(openedChooser)
            state.value = CastConnectionState(connecting = true)
        }
        compose.onNodeWithText("Connecting to TV…").assertIsDisplayed()
        compose.runOnIdle { state.value = CastConnectionState(connected = true, deviceName = "Living room") }
        compose.onNodeWithText("Connected to Living room").assertIsDisplayed()
        compose.onNodeWithText("Choose a movie").assertIsDisplayed()
        compose.runOnIdle { state.value = CastConnectionState() }
        compose.onNodeWithText("Watch on TV").assertIsDisplayed()
    }

    @Test fun narrowPlayerKeepsCastVisibleWithAllEpisodeAndAudioOptions() {
        var casts = 0
        var audioOpened = false
        var sourcesOpened = false
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    TopOverlay(
                        title = "A very long TV episode title with every player option enabled",
                        onBack = {}, onSources = { sourcesOpened = true }, showSourcesButton = true,
                        onSubtitles = {}, subtitlesActive = true,
                        onAudio = { audioOpened = true }, showAudioButton = true, audioNeedsAttention = true,
                        zoomFill = false, onToggleZoom = {}, hasEpisodes = true,
                        hasPrevious = true, hasNext = true, onPrevious = {}, onNext = {}, onEpisodes = {},
                        pipSupported = true, onPip = {},
                        castAction = { CastDestinationControl(CastConnectionState(), false) { casts++ } },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Cast to TV").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Player options").performClick()
        compose.onNodeWithText("Audio track").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Player options").performClick()
        compose.onNodeWithText("Streams").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Cast to TV").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, casts); assertTrue(audioOpened); assertTrue(sourcesOpened) }
    }
}
