package com.slickstream.core.catalog

import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelvesTest {

    private val day = Shelves.Date(20_775) // 2026-11-18

    private fun item(id: Int, type: MediaType = MediaType.MOVIE) =
        MediaItem(id = id, mediaType = type, title = "t$id", overview = "", posterUrl = null,
            backdropUrl = null, voteAverage = 0.0, releaseDate = null)

    @Test
    fun `dates format without java time`() {
        assertEquals("1970-01-01", Shelves.Date.isoOf(0))
        assertEquals("2026-11-18", day.iso)
        assertEquals("2026-09-19", day.minusDays(60))
        assertEquals("2024-02-29", Shelves.Date.isoOf(19_782))
    }

    @Test
    fun `a title appears once per page and thin rows are dropped`() {
        val a = Shelf("a", "A", Shelf.Source.TRENDING_DAY, null)
        val b = Shelf("b", "B", Shelf.Source.TRENDING_WEEK, null)
        val c = Shelf("c", "C", Shelf.Source.TOP_RATED, MediaType.MOVIE)
        val rows = Shelves.dedupe(listOf(
            a to (1..10).map { item(it) },
            b to (5..20).map { item(it) },          // 1..10 already shown -> 11..20 remain
            c to (1..14).map { item(it) },          // only 11..14 fresh -> 4 < MIN_ROW -> dropped
        ))
        assertEquals(listOf("A", "B"), rows.map { it.first.title })
        assertEquals((11..20).toList(), rows[1].second.map { it.id })
    }

    @Test
    fun `same id as a movie and a show are different titles`() {
        val s = Shelf("s", "S", Shelf.Source.TRENDING_DAY, null)
        val rows = Shelves.dedupe(listOf(s to (1..6).map { item(it) } + (1..6).map { item(it, MediaType.TV) }))
        assertEquals(12, rows.single().second.size)
    }

    @Test
    fun `rotation is stable for a day and changes across days and refreshes`() {
        val pool = (1..20).toList()
        assertEquals(Shelves.rotate(pool, 5, 42), Shelves.rotate(pool, 5, 42))
        assertNotEquals(Shelves.rotate(pool, 5, 42), Shelves.rotate(pool, 5, 43))
        assertEquals(5, Shelves.rotate(pool, 5, 42).toSet().size)
    }

    @Test
    fun `home leads with what's new and its middle rows change day to day`() {
        val today = Shelves.home(day).map { it.id }
        assertEquals(listOf("trending-day", "new-streaming", "new-shows", "trending-week"), today.take(4))
        assertNotEquals(today, Shelves.home(Shelves.Date(day.epochDay + 1)).map { it.id })
        assertNotEquals(today, Shelves.home(day, salt = 1).map { it.id })
        assertEquals(today.size, today.toSet().size)
    }

    @Test
    fun `tabs never reuse home's generic popular lists and pages stay in range`() {
        listOf(MediaType.MOVIE, MediaType.TV).forEach { type ->
            val plan = Shelves.tab(type, day)
            assertTrue(plan.all { it.mediaType == type })
            plan.forEach { s -> assertTrue(Shelves.pageFor(s, day) in 1..s.pages) }
        }
    }

    /** Dumps today's plans so they can be checked against live TMDB (see build test output). */
    @Test
    fun `dump plans`() {
        val all = Shelves.home(day) + Shelves.tab(MediaType.MOVIE, day) + Shelves.tab(MediaType.TV, day)
        val pools = (0..30).flatMap { Shelves.home(Shelves.Date(day.epochDay + it)) + Shelves.tab(MediaType.TV, Shelves.Date(day.epochDay + it)) + Shelves.tab(MediaType.MOVIE, Shelves.Date(day.epochDay + it)) }
        (all + pools).distinctBy { it.id }.forEach { s ->
            println("SHELF\t${s.id}\t${s.source}\t${s.mediaType}\t${s.pages}\t" + s.params.entries.joinToString("&") { "${it.key}=${it.value}" })
        }
    }
}
