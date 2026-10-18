package com.slickstream.cast

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.slickstream.data.vlc.VlcCastTarget
import com.slickstream.data.vlc.VlcEngine
import com.slickstream.data.vlc.VlcPlayer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VlcCastIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun converterAcceptsNativeReceiverMediaWithoutMedia3Json() {
        val info = MediaInfo.Builder("http://192.168.0.2:18080/stream")
            .setContentType("video/x-matroska")
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).build()
        val item = CastMediaItemConverter().toMediaItem(MediaQueueItem.Builder(info).build())
        assertEquals(info.contentId, item.localConfiguration?.uri.toString())
        assertEquals(info.contentType, item.localConfiguration?.mimeType)
    }

    @Test fun converterPreservesNormalMedia3Items() {
        val converter = CastMediaItemConverter()
        val item = MediaItem.Builder().setUri("https://example.test/movie.mp4")
            .setMimeType("video/mp4").setMediaId("original").build()
        assertEquals(item, converter.toMediaItem(converter.toMediaQueueItem(item)))
    }

    /** Run with tools/cast-smoke-test/receiver.py and -e castTestHost 10.0.2.2. */
    @Test fun nativeOutputConvertsProtectedInputAndSupportsPauseSeekAndCleanup() {
        val host = InstrumentationRegistry.getArguments().getString("castTestHost")
        assumeNotNull(host)
        val base = "http://$host:18081"
        val engine = VlcEngine(instrumentation.targetContext)
        val bridge = CastStreamServer("127.0.0.1", mapOf("X-Cast-Test" to "fixture"))
        val media = bridge.prepare("$base/fixture.mkv", "fixture.mkv")
        val error = AtomicReference<PlaybackException>()
        lateinit var player: VlcPlayer
        var created = false
        fun status() = JSONObject(URL("$base/status").readText())
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 45_000_000_000L
            while (System.nanoTime() < deadline) {
                error.get()?.let { throw AssertionError("Native VLC failed", it) }
                if (condition()) return
                Thread.sleep(100)
            }
            fail("$message; receiver status: ${status()}")
        }
        try {
            instrumentation.runOnMainSync {
                player = VlcPlayer(engine, instrumentation.targetContext, VlcCastTarget(host!!, 18009, 18080))
                created = true
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(failure: PlaybackException) { error.set(failure) }
                })
                player.setMediaItem(MediaItem.fromUri(media.url), 2_000)
                player.prepare()
                player.play()
            }
            await("No converted media delivered") { status().optLong("bytes") > 50_000 }
            instrumentation.runOnMainSync { player.pause() }
            await("Pause was not sent to receiver") { status().optInt("pauses") > 0 }
            instrumentation.runOnMainSync { player.play() }
            await("Play was not sent to receiver") { status().optInt("plays") > 0 }
            val loads = status().optInt("loads")
            instrumentation.runOnMainSync { player.seekTo(7_000) }
            await("Seeking did not reload the converted stream") { status().optInt("loads") > loads }
        } finally {
            instrumentation.runOnMainSync { if (created) player.release() }
            bridge.stop()
            try {
                // Releasing the Cast player must close its output even while the shared engine lives.
                assertThrows(Exception::class.java) {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 18080), 1000) }
                }
            } finally {
                engine.release()
            }
        }
        assertThrows(Exception::class.java) { URL(media.url).openConnection().apply { connectTimeout = 1000 }.getInputStream() }
    }

    @Test fun unreachableReceiverReportsErrorAndReleases() {
        val engine = VlcEngine(instrumentation.targetContext)
        val controlPort = java.net.ServerSocket(0).use { it.localPort }
        val httpPort = java.net.ServerSocket(0).use { it.localPort }
        val failed = java.util.concurrent.CountDownLatch(1)
        lateinit var player: VlcPlayer
        try {
            instrumentation.runOnMainSync {
                player = VlcPlayer(engine, instrumentation.targetContext, VlcCastTarget("127.0.0.1", controlPort, httpPort))
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failed.countDown() }
                })
                player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:1/unavailable"))
                player.prepare()
                player.play()
            }
            assertTrue("Unreachable receiver didn't report failure", failed.await(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { player.release() }
            engine.release()
        }
    }
}
