package com.slickstream.cast

import com.slickstream.data.vlc.VlcCastTarget
import org.junit.Assert.*
import org.junit.Test

class VlcCastTargetTest {
    @Test fun `receiver addresses cannot inject VLC output options`() {
        listOf("192.168.0.1},dst=file", "localhost", "256.1.1.1", "1.2.3", "::1", "1.2.3.-1").forEach {
            assertThrows(IllegalArgumentException::class.java) { VlcCastTarget(it, 8009, 18080) }
        }
    }

    @Test fun `reject invalid control and HTTP ports`() {
        listOf(0, -1, 65536).forEach {
            assertThrows(IllegalArgumentException::class.java) { VlcCastTarget("192.168.0.2", it, 18080) }
            assertThrows(IllegalArgumentException::class.java) { VlcCastTarget("192.168.0.2", 8009, it) }
        }
    }
}
