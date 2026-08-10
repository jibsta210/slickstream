package com.slickstream.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule that decides when the chunk bar is on screen. The bar is a DIAGNOSTIC — its whole
 * value is telling a real stall from a false one — so the two ways it can fail its job are pinned here:
 * being absent during a stall, and being present with nothing behind it.
 */
class PieceBarVisibilityTest {

    // --- the reported regression: a mid-playback stall with the transport auto-hidden ---

    @Test
    fun `stall with hidden controls shows the bar`() {
        // uiState stays Playing through a rebuffer and the transport hides itself after 5 s. Without
        // `stalled` in the rule this was false, and the frozen picture carried only a "5.0 MB/s" badge.
        assertTrue(
            shouldShowChunkBar(
                torrentBacked = true, starting = false, stalled = true, controlsShown = false,
            ),
        )
    }

    @Test
    fun `playing normally with hidden controls keeps the bar away`() {
        // The bar must not become permanent furniture over the film — only a stall forces it up.
        assertFalse(
            shouldShowChunkBar(
                torrentBacked = true, starting = false, stalled = false, controlsShown = false,
            ),
        )
    }

    @Test
    fun `startup buffering shows the bar with no transport to ride with`() {
        assertTrue(
            shouldShowChunkBar(
                torrentBacked = true, starting = true, stalled = false, controlsShown = false,
            ),
        )
    }

    @Test
    fun `transport up shows the bar`() {
        assertTrue(
            shouldShowChunkBar(
                torrentBacked = true, starting = false, stalled = false, controlsShown = true,
            ),
        )
    }

    // --- a direct stream has no swarm: the bar must never claim to describe one ---

    @Test
    fun `direct source never shows the bar`() {
        // No streamer, so stats are null and the piece map misses the cache: the panel could only render
        // "Downloading…" over a flat empty track — "nothing downloaded" for a stream that plays fine.
        for (starting in listOf(false, true)) {
            for (stalled in listOf(false, true)) {
                for (controls in listOf(false, true)) {
                    assertFalse(
                        "direct/starting=$starting/stalled=$stalled/controls=$controls",
                        shouldShowChunkBar(
                            torrentBacked = false,
                            starting = starting,
                            stalled = stalled,
                            controlsShown = controls,
                        ),
                    )
                }
            }
        }
    }
}
