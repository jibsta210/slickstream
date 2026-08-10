package com.slickstream.tv.components

import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the re-entry ticket that carries D-pad focus across a Details round trip.
 *
 * These are the parts that can be checked without a device: what a ticket decodes to, which surface
 * is allowed to claim it, and that it can only be spent once. The focus request itself needs a real
 * composition and is not covered here.
 */
class TvFocusTicketTest {

    private fun item(type: MediaType, id: Int) = MediaItem(
        id = id,
        mediaType = type,
        title = "t$id",
        overview = "",
        posterUrl = null,
        backdropUrl = null,
        voteAverage = 0.0,
        releaseDate = null,
    )

    private fun ticket(pending: String): Pair<TvFocusTicket, () -> String> {
        var slot = pending
        return TvFocusTicket(pending) { slot = it } to { slot }
    }

    @Test
    fun `fresh arrival is not a return`() {
        val (t, _) = ticket("")
        assertFalse(t.isReturning)
        assertEquals("", t.pendingItem)
        // Nothing may claim the anchor for a tile that was never recorded.
        assertEquals("", t.pendingItemFor("Trending"))
    }

    @Test
    fun `a recorded row and tile round trip through the saved slot`() {
        val (out, slot) = ticket("")
        out.record("Trending", item(MediaType.MOVIE, 42))

        val (back, _) = ticket(slot())
        assertTrue(back.isReturning)
        assertEquals("Trending", back.pendingRow)
        assertEquals("MOVIE-42", back.pendingItem)
    }

    @Test
    fun `only the row that was left through may claim the tile`() {
        val (out, slot) = ticket("")
        out.record("Trending", item(MediaType.TV, 7))
        val (back, _) = ticket(slot())

        assertEquals("TV-7", back.pendingItemFor("Trending"))
        // Every other row on the screen must decline, or two rows fight over focus on return.
        assertEquals("", back.pendingItemFor("Continue Watching"))
        assertEquals("", back.pendingItemFor(""))
    }

    @Test
    fun `single-surface screens use the empty row key`() {
        val (out, slot) = ticket("")
        out.record("", item(MediaType.MOVIE, 5))
        val (back, _) = ticket(slot())

        assertTrue(back.isReturning)
        assertEquals("", back.pendingRow)
        assertEquals("MOVIE-5", back.pendingItem)
    }

    @Test
    fun `a row title containing the separator still decodes`() {
        val (out, slot) = ticket("")
        out.record("Top 10 #Trending", item(MediaType.MOVIE, 1))
        val (back, _) = ticket(slot())

        assertEquals("Top 10 #Trending", back.pendingRow)
        assertEquals("MOVIE-1", back.pendingItem)
    }

    @Test
    fun `movie and TV ids in the same numeric slot are different tiles`() {
        // TMDB movie and TV ids are separate namespaces that overlap numerically; a bare id ticket
        // would restore focus onto the wrong title.
        assertEquals("MOVIE-99", tvItemKey(item(MediaType.MOVIE, 99)))
        assertEquals("TV-99", tvItemKey(item(MediaType.TV, 99)))
    }

    @Test
    fun `the restore may be claimed exactly once`() {
        val (t, _) = ticket("Trending#MOVIE-3")
        assertTrue(t.claim())
        // A row scrolling out of the LazyColumn and back re-runs its restore effect; without the
        // one-shot claim it would yank focus off wherever the user had since moved to.
        assertFalse(t.claim())
        assertFalse(t.claim())
    }

    @Test
    fun `clear spends the ticket so the next arrival starts fresh`() {
        val (t, slot) = ticket("Trending#MOVIE-3")
        t.clear()
        val (next, _) = ticket(slot())
        assertFalse(next.isReturning)
    }

    @Test
    fun `the anchor is one per screen so exactly one tile can wear it`() {
        val (t, _) = ticket("Trending#MOVIE-3")
        assertSame(t.anchor, t.anchor)
        assertFalse(t.anchor.landed)
    }
}
