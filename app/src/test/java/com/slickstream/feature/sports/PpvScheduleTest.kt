package com.slickstream.feature.sports

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpvScheduleTest {

    private val now = 1_793_844_660_000L // the measured feed's own timestamp
    private val nowS = now / 1000

    private fun game(id: Long, name: String, startS: Long, endS: Long, iframe: String = "https://host.example/embed/$id") =
        PpvStreamDto(id = id, name = name, startsAt = startS, endsAt = endS, alwaysLiveRaw = JsonPrimitive(0), iframe = iframe)

    private fun channel(id: Long, name: String) =
        PpvStreamDto(id = id, name = name, alwaysLiveRaw = JsonPrimitive(1), iframe = "https://host.example/embed/ch$id")

    private val schedule = PpvScheduleDto(
        success = true,
        timestamp = nowS,
        streams = listOf(
            PpvCategoryDto(26, "American Football", listOf(
                channel(18172, "NFL Network"),
                game(1, "Later Game", nowS + 3600, nowS + 7200),
                game(2, "Live Game", nowS - 600, nowS + 3000),
            )),
            PpvCategoryDto(37, "Basketball", listOf(
                game(3, "76ers vs. Pistons", nowS - 1200, nowS + 6000).copy(
                    sourceTag = "HD | ESPN",
                    substreams = listOf(PpvSubstreamDto(103, sourceTag = "SD | ESPN", iframe = "https://host.example/embed/3_540p")),
                ),
                game(4, "Finished Game", nowS - 20000, nowS - 10000),
            )),
            PpvCategoryDto(14, "24/7 Streams", listOf(channel(9, "ESPN"))),
            PpvCategoryDto(99, "Darts", listOf(game(5, "Old", nowS - 20000, nowS - 10000))), // nothing current
        ),
    )

    @Test
    fun `categories put NBA and NFL first, channels last, and drop sports with nothing current`() {
        val names = PpvSchedule.categories(schedule, now).map { it.name }
        assertEquals(listOf("NBA", "NFL", "24/7 Channels"), names)
    }

    @Test
    fun `live now holds live games only — no channels, nothing upcoming or finished`() {
        val live = PpvSchedule.liveEvents(schedule, now).map { it.title }
        assertEquals(setOf("Live Game", "76ers vs. Pistons"), live.toSet())
    }

    @Test
    fun `a sport lists live games, then upcoming, then its channels`() {
        val nfl = PpvSchedule.events(schedule, "ppv-26", now).map { it.title }
        assertEquals(listOf("Live Game", "Later Game", "NFL Network"), nfl)
        val nba = PpvSchedule.events(schedule, "ppv-37", now).map { it.title }
        assertEquals(listOf("76ers vs. Pistons"), nba) // the finished game is gone
    }

    @Test
    fun `an event's feeds are its embed plus substreams, resolved with the ppv referer`() {
        val feeds = PpvSchedule.streamsFor(schedule, "ppv-3")
        assertEquals(listOf("HD | ESPN", "SD | ESPN"), feeds.map { it.label })
        assertTrue(feeds.all { it.needsResolution && it.pageReferer == "https://ppv.st/" })
        assertEquals("https://host.example/", feeds.first().headers["Referer"])
        assertEquals("https://host.example", feeds.first().headers["Origin"])
    }

    @Test
    fun `live window falls back to three hours when there is no end time`() {
        assertTrue(PpvSchedule.isLive(game(7, "x", nowS - 3600, 0), now))
        assertFalse(PpvSchedule.isLive(game(8, "y", nowS - 4 * 3600, 0), now))
        assertTrue(PpvSchedule.isLive(channel(9, "z"), now))
    }
}
