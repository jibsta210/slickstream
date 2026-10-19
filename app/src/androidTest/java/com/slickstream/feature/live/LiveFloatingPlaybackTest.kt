package com.slickstream.feature.live

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.slickstream.MainActivity
import com.slickstream.feature.player.LocalPipController
import com.slickstream.feature.player.PipController
import com.slickstream.feature.player.rememberIsInPipMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real Activity window transitions with decoded video, including the same four-player layout. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveFloatingPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(100)
        }
        fail(message)
    }
    private fun isArmed(activity: MainActivity): Boolean =
        MainActivity::class.java.getDeclaredField("pipEnabled").let {
            it.isAccessible = true
            it.getBoolean(activity)
        }
    private class Tile(override val id: Int, val exo: ExoPlayer) : TileSession {
        override val title = "Game $id"
        override val uiState = MutableStateFlow<TileUiState>(TileUiState.Playing)
        override val player = MutableStateFlow<Player?>(exo)
        override fun setAudible(value: Boolean) { exo.volume = if (value) 1f else 0f }
        override fun setVideoEnabled(value: Boolean) = Unit
        override fun setVideoCap(cap: LiveMultiView.VideoCap?) = Unit
        override fun retry() = Unit
        override fun release() = exo.release()
        fun frames(): Int = exo.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount } ?: 0
    }

    @Test fun oneTwoThreeAndFourGamesKeepRenderingWhenFloatingAndAfterRestore() {
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "floating-window-test.mp4")
        instrumentation.context.assets.open("floating-window.mp4").use { input ->
            fixture.outputStream().use(input::copyTo)
        }
        val tiles = mutableListOf<Tile>()
        val count = mutableStateOf(1)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        lateinit var controller: PipController
        try {
            scenario.onActivity { host ->
                activity = host
                // Exercise the actual production host controller, without network/auth navigation.
                controller = MainActivity::class.java.getDeclaredField("pipController").let {
                    it.isAccessible = true
                    it.get(host) as PipController
                }
                repeat(4) { index ->
                    val player = ExoPlayer.Builder(host).build().apply {
                        repeatMode = Player.REPEAT_MODE_ONE
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
                        volume = if (index == 0) 1f else 0f
                        prepare()
                        play()
                    }
                    tiles += Tile(index + 1, player)
                }
                host.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalPipController provides controller) {
                            val floating = rememberIsInPipMode()
                            val visible = tiles.take(count.value)
                            LiveFloatingWindow(visible, null, true, Modifier.fillMaxSize()) {
                                when (count.value) {
                                    1 -> LiveTile(tiles[0], true, false, {}, Modifier.fillMaxSize(), !floating)
                                    2 -> PipLayout(visible, 1, !floating, { FocusRequester() }, {}, {}, {})
                                    else -> GridLayout(visible, 1, !floating, { FocusRequester() }, count.value < 4, {}, {})
                                }
                            }
                        }
                    }
                }
            }
            for (size in 1..4) {
                instrumentation.runOnMainSync { count.value = size }
                await("$size games did not render") { tiles.take(size).all { it.frames() > 5 } }
                await("Floating window was not armed") { isArmed(activity) }
                val before = IntArray(size)
                instrumentation.runOnMainSync {
                    tiles.take(size).forEachIndexed { i, tile -> before[i] = tile.frames() }
                    if (size == 1) controller.enter()
                }
                if (size > 1) instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                await("$size games did not enter floating window") { activity.isInPictureInPictureMode && controller.isInPip.value }
                await("Video stopped rendering in floating window ($size games)") {
                    tiles.take(size).withIndex().all { (i, tile) -> tile.frames() > before[i] + 5 }
                }
                instrumentation.runOnMainSync {
                    assertTrue(controller.isInPip.value)
                    assertEquals(1f, tiles[0].exo.volume, 0f)
                    assertTrue(tiles.drop(1).all { it.exo.volume == 0f })
                    activity.startActivity(Intent(activity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                await("Floating window did not restore") { !activity.isInPictureInPictureMode && !controller.isInPip.value }
                instrumentation.runOnMainSync { tiles.take(size).forEachIndexed { i, tile -> before[i] = tile.frames() } }
                await("Video stopped after restoring ($size games)") {
                    tiles.take(size).withIndex().all { (i, tile) -> tile.frames() > before[i] + 5 }
                }
            }
            instrumentation.runOnMainSync { activity.setContent { Text("Browsing") } }
            await("Leaving sports did not disarm auto-floating") { !isArmed(activity) }
        } finally {
            instrumentation.runOnMainSync { tiles.forEach { it.release() } }
            scenario.close()
            fixture.delete()
        }
    }
}
