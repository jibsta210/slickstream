package com.slickstream.data.source

import com.slickstream.data.source.ImdbSeasonMatcher.Candidate
import com.slickstream.data.source.ImdbSeasonMatcher.CandidateEpisode
import com.slickstream.data.source.ImdbSeasonMatcher.MovieCandidate
import com.slickstream.data.source.ImdbSeasonMatcher.TmdbSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are the MEASURED data behind the "Monster: The Lizzie Borden Story → no sources" report:
 * TMDB lists each Netflix Monster story as its own one-season show with a blank imdb id, while
 * IMDB/Cinemeta files them as seasons 1–4 of tt13207736 (Cinemeta stamps every episode 11:00Z).
 */
class ImdbSeasonMatcherTest {

    // --- fixtures ---------------------------------------------------------------------------------

    private fun season(imdbSeason: Int, count: Int, premiere: String, stamp: String = "T11:00:00.000Z") =
        (1..count).map { CandidateEpisode(imdbSeason, it, premiere + stamp) }

    private val monster = Candidate(
        imdbId = "tt13207736",
        name = "Monster",
        episodes = season(1, 10, "2022-09-21") + season(2, 9, "2024-09-19") +
            season(3, 8, "2025-10-03") + season(4, 8, "2026-09-17"),
    )
    private val lizzieChronicles = Candidate(
        imdbId = "tt4145760",
        name = "The Lizzie Borden Chronicles",
        episodes = season(1, 8, "2015-04-05"),
    )
    private val monsterAnime2004 = Candidate(
        imdbId = "tt0434706",
        name = "Monster",
        episodes = season(1, 74, "2004-04-07"),
    )
    private val storyMonster = Candidate(
        imdbId = "tt9999001",
        name = "The Story Monster",
        episodes = season(1, 6, "2019-03-02"),
    )
    private val allCandidates = listOf(lizzieChronicles, monsterAnime2004, storyMonster, monster)

    // --- the four Monster entries -----------------------------------------------------------------

    @Test
    fun `Lizzie Borden TMDB season 1 maps to Monster IMDB season 4 despite decoys`() {
        val m = ImdbSeasonMatcher.match(
            "Monster: The Lizzie Borden Story",
            listOf(TmdbSeason(1, "2026-09-17", 8)),
            allCandidates,
        )
        assertNotNull(m)
        assertEquals("tt13207736", m!!.imdbId)
        assertEquals(mapOf(1 to 4), m.seasonMap)
        assertFalse(m.identity)
        assertEquals(4, m.imdbSeasonFor(1))
    }

    @Test
    fun `Dahmer maps season 1 to 1 and ignores the TMDB specials season`() {
        val m = ImdbSeasonMatcher.match(
            "DAHMER - Monster: The Jeffrey Dahmer Story",
            listOf(TmdbSeason(0, "2022-11-28", 1), TmdbSeason(1, "2022-09-21", 10)),
            allCandidates,
        )
        assertEquals("tt13207736", m!!.imdbId)
        assertEquals(mapOf(1 to 1), m.seasonMap)
        assertTrue(m.identity)
        assertEquals(1, m.imdbSeasonFor(1))
    }

    @Test
    fun `Menendez maps to season 2 even though TMDB pluralises the title as Monsters`() {
        val m = ImdbSeasonMatcher.match(
            "Monsters: The Lyle and Erik Menendez Story",
            listOf(TmdbSeason(1, "2024-09-19", 9)),
            allCandidates,
        )
        assertEquals("tt13207736", m!!.imdbId)
        assertEquals(2, m.imdbSeasonFor(1))
    }

    @Test
    fun `Ed Gein maps to season 3`() {
        val m = ImdbSeasonMatcher.match(
            "Monster: The Ed Gein Story",
            listOf(TmdbSeason(1, "2025-10-03", 8)),
            allCandidates,
        )
        assertEquals("tt13207736", m!!.imdbId)
        assertEquals(3, m.imdbSeasonFor(1))
    }

    @Test
    fun `anthology mapping never extrapolates to an unmapped TMDB season`() {
        val m = ImdbSeasonMatcher.match(
            "Monster: The Lizzie Borden Story",
            listOf(TmdbSeason(1, "2026-09-17", 8)),
            allCandidates,
        )!!
        assertNull(m.imdbSeasonFor(2))
        assertNull(m.imdbSeasonFor(0))
    }

    // --- date window ------------------------------------------------------------------------------

    @Test
    fun `one day of UTC skew either way still maps`() {
        for (tmdbDate in listOf("2026-09-16", "2026-09-18")) {
            val m = ImdbSeasonMatcher.match(
                "Monster: The Lizzie Borden Story",
                listOf(TmdbSeason(1, tmdbDate, 8)),
                listOf(monster),
            )
            assertEquals(tmdbDate, 4, m?.imdbSeasonFor(1))
        }
        // A late-evening US premiere stamped the NEXT day in UTC.
        val skewed = monster.copy(episodes = season(4, 8, "2026-09-18", "T03:00:00.000Z"))
        val m = ImdbSeasonMatcher.match(
            "Monster: The Lizzie Borden Story",
            listOf(TmdbSeason(1, "2026-09-17", 8)),
            listOf(skewed),
        )
        assertEquals(4, m?.imdbSeasonFor(1))
    }

    @Test
    fun `five days apart is a different season and does not map`() {
        for (tmdbDate in listOf("2026-09-12", "2026-09-22")) {
            assertNull(
                tmdbDate,
                ImdbSeasonMatcher.match(
                    "Monster: The Lizzie Borden Story",
                    listOf(TmdbSeason(1, tmdbDate, 8)),
                    allCandidates,
                ),
            )
        }
    }

    @Test
    fun `no premiere date match returns null`() {
        assertNull(
            ImdbSeasonMatcher.match(
                "Monster: The Lizzie Borden Story",
                listOf(TmdbSeason(1, "2027-01-01", 8)),
                allCandidates,
            ),
        )
    }

    @Test
    fun `no dated positive TMDB season returns null`() {
        assertNull(
            ImdbSeasonMatcher.match(
                "Monster",
                listOf(TmdbSeason(0, "2022-09-21", 1), TmdbSeason(1, null, 8), TmdbSeason(2, "soon", 8)),
                listOf(monster),
            ),
        )
    }

    @Test
    fun `several IMDB seasons in the window are split by episode count`() {
        val backToBack = Candidate(
            "tt5550001", "Double Drop",
            season(1, 6, "2023-05-01") + season(2, 8, "2023-05-02"),
        )
        val m = ImdbSeasonMatcher.match("Double Drop", listOf(TmdbSeason(1, "2023-05-01", 8)), listOf(backToBack))
        assertEquals(2, m?.imdbSeasonFor(1))

        // Same counts on both -> ambiguous -> that season stays unmapped -> no mapping at all.
        val sameCounts = backToBack.copy(episodes = season(1, 8, "2023-05-01") + season(2, 8, "2023-05-02"))
        assertNull(ImdbSeasonMatcher.match("Double Drop", listOf(TmdbSeason(1, "2023-05-01", 8)), listOf(sameCounts)))
    }

    // --- ordinary shows ---------------------------------------------------------------------------

    @Test
    fun `normal show maps identity and extrapolates to an undated TMDB season`() {
        val show = Candidate(
            "tt7654321", "Harbor Lights",
            season(1, 10, "2019-01-10") + season(2, 10, "2020-01-09") + season(3, 10, "2021-01-14"),
        )
        val m = ImdbSeasonMatcher.match(
            "Harbor Lights",
            listOf(
                TmdbSeason(1, "2019-01-10", 10),
                TmdbSeason(2, "2020-01-09", 10),
                TmdbSeason(3, "2021-01-14", 10),
                TmdbSeason(4, null, 0),
            ),
            listOf(show),
        )!!
        assertEquals("tt7654321", m.imdbId)
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 3), m.seasonMap)
        assertTrue(m.identity)
        assertEquals(4, m.imdbSeasonFor(4))
    }

    @Test
    fun `a candidate that does not map the first dated TMDB season is rejected`() {
        val show = Candidate("tt7654321", "Harbor Lights", season(1, 10, "2020-01-09"))
        assertNull(
            ImdbSeasonMatcher.match(
                "Harbor Lights",
                listOf(TmdbSeason(1, "2019-01-10", 10), TmdbSeason(2, "2020-01-09", 10)),
                listOf(show),
            ),
        )
    }

    @Test
    fun `mapping is injective - two TMDB seasons claiming one IMDB season are both dropped`() {
        val show = Candidate(
            "tt7654321", "Harbor Lights",
            season(1, 10, "2019-01-10") + season(2, 6, "2020-01-09"),
        )
        val m = ImdbSeasonMatcher.match(
            "Harbor Lights",
            listOf(TmdbSeason(1, "2019-01-10", 10), TmdbSeason(2, "2020-01-09", 3), TmdbSeason(3, "2020-01-10", 3)),
            listOf(show),
        )!!
        assertEquals(mapOf(1 to 1), m.seasonMap)

        // When the dropped pair includes the FIRST season, the candidate is invalid altogether.
        val firstShared = ImdbSeasonMatcher.match(
            "Harbor Lights",
            listOf(TmdbSeason(1, "2019-01-10", 10), TmdbSeason(2, "2019-01-11", 10)),
            listOf(show),
        )
        assertNull(firstShared)
    }

    // --- choosing between candidates --------------------------------------------------------------

    @Test
    fun `two equally good candidates are refused`() {
        val twin = monster.copy(imdbId = "tt13207737")
        assertNull(
            ImdbSeasonMatcher.match(
                "Monster: The Lizzie Borden Story",
                listOf(TmdbSeason(1, "2026-09-17", 8)),
                listOf(monster, twin),
            ),
        )
    }

    @Test
    fun `duplicate rows of the same imdb id are not a tie`() {
        val m = ImdbSeasonMatcher.match(
            "Monster: The Lizzie Borden Story",
            listOf(TmdbSeason(1, "2026-09-17", 8)),
            listOf(monster, monster),
        )
        assertEquals("tt13207736", m?.imdbId)
    }

    @Test
    fun `exact title breaks a tie in mapped seasons`() {
        val exact = Candidate("tt8880001", "Monster: The Lizzie Borden Story", season(1, 8, "2026-09-17"))
        val m = ImdbSeasonMatcher.match(
            "Monster: The Lizzie Borden Story",
            listOf(TmdbSeason(1, "2026-09-17", 8)),
            listOf(monster, exact),
        )
        assertEquals("tt8880001", m?.imdbId)
        assertTrue(m!!.identity)
    }

    @Test
    fun `candidate with more mapped seasons wins`() {
        val full = Candidate("tt1110001", "Harbor Lights", season(1, 10, "2019-01-10") + season(2, 10, "2020-01-09"))
        val partial = Candidate("tt1110002", "Harbor Lights", season(1, 10, "2019-01-10"))
        val m = ImdbSeasonMatcher.match(
            "Harbor Lights",
            listOf(TmdbSeason(1, "2019-01-10", 10), TmdbSeason(2, "2020-01-09", 10)),
            listOf(partial, full),
        )
        assertEquals("tt1110001", m?.imdbId)
    }

    @Test
    fun `non tt ids are ignored`() {
        val bad = monster.copy(imdbId = "kitsu:1234")
        assertNull(
            ImdbSeasonMatcher.match(
                "Monster: The Lizzie Borden Story",
                listOf(TmdbSeason(1, "2026-09-17", 8)),
                listOf(bad),
            ),
        )
    }

    // --- titlesCompatible -------------------------------------------------------------------------

    @Test
    fun `titlesCompatible truth table`() {
        assertTrue(ImdbSeasonMatcher.titlesCompatible("DAHMER - Monster: The Jeffrey Dahmer Story", "Monster"))
        assertTrue(ImdbSeasonMatcher.titlesCompatible("Monster: The Lizzie Borden Story", "Monster"))
        assertTrue(ImdbSeasonMatcher.titlesCompatible("Monsters: The Lyle and Erik Menendez Story", "Monster"))
        assertFalse(ImdbSeasonMatcher.titlesCompatible("Monster: The Lizzie Borden Story", "The Lizzie Borden Chronicles"))
        // Compatible by name — it is the DATE match that rejects this one.
        assertTrue(ImdbSeasonMatcher.titlesCompatible("Monster: The Lizzie Borden Story", "The Story Monster"))
        assertTrue(ImdbSeasonMatcher.titlesCompatible("Law & Order", "Law and Order"))
        assertTrue(ImdbSeasonMatcher.titlesCompatible("Pokémon", "POKEMON"))
        assertTrue(ImdbSeasonMatcher.titlesCompatible("Grey's Anatomy", "Greys Anatomy"))
        assertFalse(ImdbSeasonMatcher.titlesCompatible("Breaking Bad", "Better Call Saul"))
        assertFalse(ImdbSeasonMatcher.titlesCompatible("The", "The"))
        assertFalse(ImdbSeasonMatcher.titlesCompatible("", "Monster"))
    }

    // --- epochDay ---------------------------------------------------------------------------------

    @Test
    fun `epochDay matches the civil calendar`() {
        assertEquals(0L, ImdbSeasonMatcher.epochDay("1970-01-01"))
        assertEquals(-1L, ImdbSeasonMatcher.epochDay("1969-12-31"))
        assertEquals(11017L, ImdbSeasonMatcher.epochDay("2000-03-01"))
        assertEquals(19782L, ImdbSeasonMatcher.epochDay("2024-02-29"))
        assertEquals(20713L, ImdbSeasonMatcher.epochDay("2026-09-17"))
        assertEquals(20713L, ImdbSeasonMatcher.epochDay("2026-09-17T11:00:00.000Z"))
    }

    @Test
    fun `epochDay rejects malformed dates`() {
        for (bad in listOf("", "2026", "2026-9-17", "2026/09/17", "2026-13-01", "2026-00-10", "2025-02-29", "2026-04-31", "abcd-ef-gh")) {
            assertNull(bad, ImdbSeasonMatcher.epochDay(bad))
        }
    }

    // --- matchMovie -------------------------------------------------------------------------------

    @Test
    fun `matchMovie prefers the exact year`() {
        val candidates = listOf(
            MovieCandidate("tt0113277", "Heat", 1995),
            MovieCandidate("tt0000002", "Heat", 1996),
        )
        assertEquals("tt0113277", ImdbSeasonMatcher.matchMovie("Heat", 1995, candidates))
    }

    @Test
    fun `matchMovie refuses a same-titled film from another year`() {
        val candidates = listOf(MovieCandidate("tt0113277", "Heat", 1995))
        assertNull(ImdbSeasonMatcher.matchMovie("Heat", 1996, candidates))
        assertNull(ImdbSeasonMatcher.matchMovie("Heat", 1994, candidates))
    }

    @Test
    fun `matchMovie refuses duplicates and a missing year`() {
        val dupes = listOf(MovieCandidate("tt0113277", "Heat", 1995), MovieCandidate("tt0000004", "Heat", 1995))
        assertNull(ImdbSeasonMatcher.matchMovie("Heat", 1995, dupes))
        assertNull(ImdbSeasonMatcher.matchMovie("Heat", null, listOf(MovieCandidate("tt0113277", "Heat", 1995))))
        assertNull(ImdbSeasonMatcher.matchMovie("Heat", 1995, listOf(MovieCandidate("tt0113277", "Heat", null))))
    }

    @Test
    fun `matchMovie requires title equality, not containment`() {
        assertNull(ImdbSeasonMatcher.matchMovie("Monster", 2003, listOf(MovieCandidate("tt0340855", "Monster House", 2003))))
        assertEquals(
            "tt0340855",
            ImdbSeasonMatcher.matchMovie("Monster", 2003, listOf(MovieCandidate("tt0340855", "MONSTER", 2003))),
        )
    }

    // ── anthology entry guard (measured: Torrentio tt13207736:1:1 is mostly Lizzie Borden) ──────────

    @Test
    fun `entry tokens name the story, not the series it is filed under`() {
        assertEquals(setOf("dahmer", "jeffrey"), ImdbSeasonMatcher.entryTokens("DAHMER - Monster: The Jeffrey Dahmer Story", "Monster"))
        assertEquals(setOf("lizzie", "borden"), ImdbSeasonMatcher.entryTokens("Monster: The Lizzie Borden Story", "Monster"))
        assertEquals(setOf("gein"), ImdbSeasonMatcher.entryTokens("Monster: The Ed Gein Story", "Monster"))
        assertEquals(setOf("lyle", "erik", "menendez"), ImdbSeasonMatcher.entryTokens("Monsters: The Lyle and Erik Menendez Story", "Monster"))
        assertTrue(ImdbSeasonMatcher.entryTokens("Breaking Bad", "Breaking Bad").isEmpty())
    }

    @Test
    fun `dahmer keeps its own files and drops sibling stories filed under 1x1`() {
        val dahmer = ImdbSeasonMatcher.entryTokens("DAHMER - Monster: The Jeffrey Dahmer Story", "Monster")
        // real file names from Torrentio's tt13207736:1:1 answer
        assertTrue(ImdbSeasonMatcher.namesEntry("S01 Dahmer/Dahmer_S01E01 [10Bit HDR] [1080p.WEB-DL.H265-FT].mkv", dahmer))
        assertTrue(ImdbSeasonMatcher.namesEntry("DAHMER.S01E01.1080p.WEBRip.x265-RARBG[eztv.re].mp4", dahmer))
        assertTrue(ImdbSeasonMatcher.namesEntry("Monster The Jeffrey Dahmer Story - S01E01 - Episode One.mkv", dahmer))
        assertFalse(ImdbSeasonMatcher.namesEntry("Monster.The.Lizzie.Borden.Story.S01E01.Bloodbath.2160p.NF.WEB-DL.mkv", dahmer))
        assertFalse(ImdbSeasonMatcher.namesEntry("S04 Historia Lizzie Borden/Potwór_ Historia Lizzie Borden_S01E01.mkv", dahmer))
        assertFalse(ImdbSeasonMatcher.namesEntry("Monster.The.Ed.Gein.Story.S01E01.2160p.NF.WEB-DL.mkv", dahmer))
    }

    @Test
    fun `no entry tokens means no guard`() {
        assertTrue(ImdbSeasonMatcher.namesEntry("Anything.S01E01.mkv", emptySet()))
    }

    @Test
    fun `a dated season that failed to match is never extrapolated`() {
        val show = Candidate("tt7654321", "Harbor Lights", season(1, 10, "2019-01-10") + season(2, 10, "2020-01-09"))
        val m = ImdbSeasonMatcher.match(
            "Harbor Lights",
            listOf(
                TmdbSeason(1, "2019-01-10", 10),
                TmdbSeason(2, "2020-01-09", 10),
                TmdbSeason(3, "2021-06-01", 10), // dated, but IMDB has nothing near it
                TmdbSeason(4, null, 0),          // undated: nothing to check, identity still applies
            ),
            listOf(show),
        )!!
        assertNull(m.imdbSeasonFor(3))
        assertEquals(4, m.imdbSeasonFor(4))
    }
}
