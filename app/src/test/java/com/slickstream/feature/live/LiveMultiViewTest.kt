package com.slickstream.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMultiViewTest {

    // --- Layout -----------------------------------------------------------------------------------

    @Test
    fun `layout follows the number of games`() {
        assertEquals(LiveMultiView.Layout.SINGLE, LiveMultiView.layoutFor(1, expanded = false))
        assertEquals(LiveMultiView.Layout.PIP, LiveMultiView.layoutFor(2, expanded = false))
        assertEquals(LiveMultiView.Layout.GRID, LiveMultiView.layoutFor(3, expanded = false))
        assertEquals(LiveMultiView.Layout.GRID, LiveMultiView.layoutFor(4, expanded = false))
    }

    @Test
    fun `an expanded tile is always SINGLE regardless of game count`() {
        // Expanding one game to full screen collapses the layout no matter how many are running.
        assertEquals(LiveMultiView.Layout.SINGLE, LiveMultiView.layoutFor(4, expanded = true))
        assertEquals(LiveMultiView.Layout.SINGLE, LiveMultiView.layoutFor(2, expanded = true))
    }

    @Test
    fun `zero games is still a valid single layout`() {
        assertEquals(LiveMultiView.Layout.SINGLE, LiveMultiView.layoutFor(0, expanded = false))
    }

    // --- Video capping (decoder load) -------------------------------------------------------------

    @Test
    fun `the full-screen picture is never capped`() {
        assertNull(LiveMultiView.videoCapFor(LiveMultiView.Layout.SINGLE, isPrimary = true))
        assertNull(LiveMultiView.videoCapFor(LiveMultiView.Layout.PIP, isPrimary = true))
    }

    @Test
    fun `small tiles are capped so four fit in the decoder budget`() {
        // A 960x540 grid cell has no use for a 1080p rendition; capping is what makes four reachable.
        assertEquals(LiveMultiView.GRID_CAP, LiveMultiView.videoCapFor(LiveMultiView.Layout.GRID, isPrimary = true))
        assertEquals(LiveMultiView.GRID_CAP, LiveMultiView.videoCapFor(LiveMultiView.Layout.GRID, isPrimary = false))
        assertEquals(LiveMultiView.PIP_CAP, LiveMultiView.videoCapFor(LiveMultiView.Layout.PIP, isPrimary = false))
    }

    // --- Decoder release when a tile is off-screen ------------------------------------------------

    @Test
    fun `only the expanded tile keeps its decoder`() {
        // Expanding tile 2 must free every other tile's codec — an invisible decoder is the most
        // expensive way to do nothing on a TV box, and the whole reason four is ever possible.
        assertTrue(LiveMultiView.videoEnabledFor(slotId = 2, expandedId = 2))
        assertFalse(LiveMultiView.videoEnabledFor(slotId = 0, expandedId = 2))
        assertFalse(LiveMultiView.videoEnabledFor(slotId = 1, expandedId = 2))
    }

    @Test
    fun `with nothing expanded every tile decodes`() {
        assertTrue(LiveMultiView.videoEnabledFor(slotId = 0, expandedId = null))
        assertTrue(LiveMultiView.videoEnabledFor(slotId = 3, expandedId = null))
    }

    // --- Audio ownership --------------------------------------------------------------------------

    @Test
    fun `removing a non-audible tile leaves the audible one alone`() {
        assertEquals(2, LiveMultiView.audibleAfterRemoval(remaining = listOf(1, 2, 3), removedId = 0, currentAudible = 2))
    }

    @Test
    fun `removing the audible tile moves audio to a remaining game, never to silence`() {
        // A multiview that goes silent reads as broken. Audio must land on someone.
        assertEquals(1, LiveMultiView.audibleAfterRemoval(remaining = listOf(1, 2), removedId = 0, currentAudible = 0))
    }

    @Test
    fun `removing the last game leaves nothing audible`() {
        assertNull(LiveMultiView.audibleAfterRemoval(remaining = emptyList(), removedId = 0, currentAudible = 0))
    }

    // --- Capacity + cells -------------------------------------------------------------------------

    @Test
    fun `can add until the ceiling`() {
        assertTrue(LiveMultiView.canAdd(0))
        assertTrue(LiveMultiView.canAdd(3))
        assertFalse(LiveMultiView.canAdd(LiveMultiView.MAX_SLOTS))
    }

    @Test
    fun `cells fill left-to-right, top-to-bottom`() {
        assertEquals(LiveMultiView.Cell(0, 0), LiveMultiView.cellFor(0))
        assertEquals(LiveMultiView.Cell(0, 1), LiveMultiView.cellFor(1))
        assertEquals(LiveMultiView.Cell(1, 0), LiveMultiView.cellFor(2))
        assertEquals(LiveMultiView.Cell(1, 1), LiveMultiView.cellFor(3))
    }

    @Test
    fun `the decoder-limit message states the real ceiling for this box`() {
        assertTrue(LiveMultiView.decoderLimitMessage(2).contains("2"))
        assertTrue(LiveMultiView.decoderLimitMessage(2).contains("3"))
    }
}
