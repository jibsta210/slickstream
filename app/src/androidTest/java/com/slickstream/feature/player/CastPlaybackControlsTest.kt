package com.slickstream.feature.player

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CastPlaybackControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transportVolumeAndReturnToPhoneStayVisibleAndClickable() {
        lateinit var player: TestPlayer
        compose.runOnUiThread { player = TestPlayer() }
        val muted = mutableStateOf(false)
        val volume = mutableStateOf(0.4f)
        var returned = false
        compose.setContent {
            MaterialTheme {
                CastPlaybackControls(player, false, true, volume.value, muted.value,
                    onVolume = { volume.value = it }, onMute = { muted.value = !muted.value },
                    onRepairAudio = {}, onPlayOnPhone = { returned = true },
                    modifier = Modifier.fillMaxSize().padding(top = 88.dp, bottom = 8.dp))
            }
        }
        compose.onNodeWithContentDescription("Pause casting").assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse(player.playWhenReady) }
        compose.onNodeWithContentDescription("Play casting").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(player.playWhenReady) }
        compose.onNodeWithContentDescription("Cast playback position").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { assertEquals(60_000L, player.lastSeekPosition) }
        compose.onNodeWithContentDescription("Mute TV").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Unmute TV").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("TV volume").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        compose.runOnIdle { assertEquals(0.7f, volume.value, 0.01f) }
        compose.onNodeWithText("Play on phone").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(returned) }
        compose.onNodeWithText("Play on this device").assertDoesNotExist()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = InstrumentationRegistry.getArguments().getString("castScreenshot")
        if (name != null) {
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
            file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.runOnUiThread { player.release() }
    }

    @Test fun directCastingOffersAudioRepairWithoutCoveringTransport() {
        lateinit var player: TestPlayer
        var repaired = false
        compose.runOnUiThread { player = TestPlayer() }
        compose.setContent {
            MaterialTheme {
                CastPlaybackControls(player, false, false, 1f, false, {}, {},
                    onRepairAudio = { repaired = true }, onPlayOnPhone = {}, modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("Fix sound").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Pause casting").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(repaired); assertFalse(player.playWhenReady) }
        compose.runOnUiThread { player.release() }
    }

    private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var playing = true
        private var position = 20_000L
        var lastSeekPosition = -1L
            private set
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_RELEASE).build())
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(MediaItemData.Builder("cast")
                .setMediaItem(MediaItem.fromUri("https://example.test/movie.mp4"))
                .setDurationUs(120_000_000).setIsSeekable(true).build()))
            .setContentPositionMs(position).build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            invalidateState()
            return Futures.immediateVoidFuture()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            lastSeekPosition = positionMs
            position = positionMs
            invalidateState()
            return Futures.immediateVoidFuture()
        }
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }
}
