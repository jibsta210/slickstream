package com.slickstream.data.torrent

import com.slickstream.data.torrent.EpisodeFileMatcher.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeFileMatcherTest {

    private fun m(path: String, s: Int, e: Int) = EpisodeFileMatcher.matches(path, s, e)

    private fun idx(names: List<String>, s: Int?, e: Int?) =
        EpisodeFileMatcher.indexOf(names, s, e) { it.substringAfterLast('/').substringBeforeLast('.') != "readme" }

    private fun resolve(names: List<String>, s: Int, e: Int) =
        EpisodeFileMatcher.resolve(names, s, e) { true }

    // =============================================================================================
    // Report #1 — Law & Order S07, compact "710 - Family Business.avi"
    // =============================================================================================

    @Test
    fun `compact SEE numbering is recognised - the Law and Order S07 pack`() {
        // Real file list from the failure: "701 - Causa Mortis.avi" … "710 - Family Business.avi".
        // Every one of these was previously unmatched, so a pack of 23 ordinary episodes was rejected
        // as an "archive/RAR release".
        assertTrue(m("710 - Family Business.avi", 7, 10))
        assertTrue(m("701 - Causa Mortis.avi", 7, 1))
        assertTrue(m("Law and Order S07/705 - Corruption.avi", 7, 5))
    }

    @Test
    fun `compact numbering does not match a neighbouring episode`() {
        assertFalse(m("701 - Causa Mortis.avi", 7, 10))
        assertFalse(m("710 - Family Business.avi", 7, 1))
    }

    @Test
    fun `zero padded compact numbering works`() {
        assertTrue(m("0710 - Family Business.avi", 7, 10))
        assertTrue(m("1210 - Something.avi", 12, 10))
    }

    @Test
    fun `a longer number never satisfies a shorter request`() {
        assertFalse(m("1710 - Later Season.avi", 7, 10))
        assertFalse(m("7101 - Odd.avi", 7, 10))
    }

    @Test
    fun `folder text does not supply a compact match`() {
        // The containing folder carries show/quality text where these digits legitimately appear;
        // only the file name is trusted for the compact form.
        assertFalse(m("Season 710 Collection/Some.Episode.avi", 7, 10))
    }

    // =============================================================================================
    // Report #2 — Octonauts: Above & Beyond S01E15, a COMBINED-episode pack
    // =============================================================================================

    @Test
    fun `combined episode file satisfies both of its episodes - the Octonauts pack`() {
        // Octonauts S01 is 25 episodes on TVDB, but Netflix ships the 11-minute segments PAIRED, so the
        // season pack holds 13 files and episode 15 exists ONLY as the second number of this name.
        // The old regex anchored `e` on the digits, read E14 and stopped -> nothing matched -> the
        // engine refused to pick among 13 videos -> a healthy pack hard-failed.
        val f = "Octonauts.Above.and.Beyond.S01E14E15.The.Octonauts.and.the.Hurricane.Hunter.Adventure." +
            "-.The.Octonauts.and.the.Little.Goby.1080p.NF.WEB-DL.DDP5.1.x264-LAZY.mkv"
        assertTrue(m(f, 1, 15))
        assertTrue(m(f, 1, 14))
        assertFalse(m(f, 1, 13))
        assertFalse(m(f, 1, 16))
        assertFalse(m(f, 2, 15))
    }

    @Test
    fun `the whole Octonauts season pack resolves every episode`() {
        // 1 + twelve pairs = 13 files for a 25-episode season.
        val names = listOf("Octonauts.Above.and.Beyond.S01E01.1080p.NF.WEBRip.x264-AGLET.mkv") +
            (2..25 step 2).map {
                "Octonauts.Above.and.Beyond.S01E%02dE%02d.1080p.NF.WEBRip.x264-AGLET.mkv".format(it, it + 1)
            }
        assertEquals(13, names.size)
        assertEquals(0, idx(names, 1, 1))
        assertEquals(7, idx(names, 1, 15))   // E14E15 is the 7th pair, at index 7
        assertEquals(7, idx(names, 1, 14))
        assertEquals(12, idx(names, 1, 25))
        // 26 does not exist; the pack is readable, so this is "absent", not "unparseable".
        assertEquals(null, idx(names, 1, 26))
    }

    @Test
    fun `dashed and short-dashed combined forms work`() {
        assertTrue(m("Greys.Anatomy.S02E01-E03.avi", 2, 3))
        assertTrue(m("Greys.Anatomy.S02E01-E03.avi", 2, 2))   // stated range, not a guess
        assertTrue(m("Greys.Anatomy.S02E01-03.avi", 2, 3))
        assertTrue(m("Show.S01E15-16.1080p.mkv", 1, 16))
        assertTrue(m("Show.1x15-16.mkv", 1, 16))
        assertTrue(m("Show.1x15-1x16.mkv", 1, 16))
        assertFalse(m("Greys.Anatomy.S02E01-E03.avi", 2, 4))
    }

    @Test
    fun `an absurd span is a season label, not a 25-episode file`() {
        // "S01E01-E25" on one file is a pack LABEL. Expanding it would make one file answer for every
        // episode in the season — the loosest possible rule.
        assertTrue(m("Show.S01E01-E25.COMPLETE.mkv", 1, 1))
        assertFalse(m("Show.S01E01-E25.COMPLETE.mkv", 1, 15))
    }

    @Test
    fun `a resolution after an episode code is not read as the end of a range`() {
        // "S01E05.1080p" must not parse as "episodes 5 through 1080", and "S01E05-720p" must not
        // silently become a 715-episode range either.
        assertFalse(m("Show.S01E05.1080p.mkv", 1, 6))
        assertFalse(m("Show.S01E05-720p.mkv", 1, 6))
        assertTrue(m("Show.S01E05.1080p.mkv", 1, 5))
    }

    // =============================================================================================
    // NEGATIVE cases — a resolution / year / codec number is never an episode
    // =============================================================================================

    @Test
    fun `a resolution is never mistaken for an episode code`() {
        // S7E20 would otherwise match the "720" in 720p, and S10E80 the "1080" in 1080p — silently
        // playing the wrong episode, which is worse than an error because nothing tells the viewer.
        assertFalse(m("Show.S07.720p.WEB-DL.avi", 7, 20))
        assertFalse(m("Show.1080p.x264.mkv", 10, 80))
        assertFalse(m("Show.2160p.mkv", 21, 60))
        // ...but a real episode file that merely SITS in a 720p folder still matches.
        assertTrue(m("Show.S07.720p.WEB-DL/720 - The One.avi", 7, 20))
    }

    @Test
    fun `an UPPER CASE resolution is also never an episode code`() {
        // The compact guard was built without (?i) while every sibling pattern had it, so "720P" and
        // "1080I" sailed through. The old test only covered lower case, so the suite stayed green.
        assertFalse(m("Show.S07.720P.WEB-DL.avi", 7, 20))
        assertFalse(m("Show S01E15 1080P WEB.mkv", 10, 80))
        assertFalse(m("Show.2160P.mkv", 21, 60))
        assertFalse(m("Show.1080I.mkv", 10, 80))
    }

    @Test
    fun `a codec tag is never an episode code`() {
        // Deterministic, not probabilistic: any season 2 with 64+ episodes (daily animation blocks,
        // telenovelas) would have matched x264 for E64 every single time.
        assertFalse(m("Show.WEB-DL.x264-GROUP.mkv", 2, 64))
        assertFalse(m("Show.WEB-DL.x265-GROUP.mkv", 2, 65))
        assertFalse(m("Show.HDTV.H265.mkv", 2, 65))
        assertFalse(m("Show.S02E10.1920x1080.mkv", 10, 80))
    }

    @Test
    fun `a bracketed year is never an episode code`() {
        assertFalse(m("Show (2021) - Pilot.mkv", 20, 21))
        assertFalse(m("Show [2019].mkv", 20, 19))
    }

    @Test
    fun `a later season folder never satisfies an earlier season request`() {
        // The season number had no trailing-digit boundary, so "Season 1" matched the "Season 1" PREFIX
        // of "Season 10"/"Season 12" and a complete-series pack handed back the wrong season's episode.
        assertFalse(m("Show/Season 10/Episode 5.mkv", 1, 5))
        assertFalse(m("Show Complete/Season 12/Episode 15 - Late.mkv", 1, 15))
        assertTrue(m("Show/Season 10/Episode 5.mkv", 10, 5))
        assertTrue(m("Show/Season 1/Episode 15.mkv", 1, 15))
    }

    @Test
    fun `season and episode words must be in the same or the next path component`() {
        // With an unbounded gap the season folder and the episode file need not be related at all.
        assertFalse(m("Season 1/Show/Extras/Interviews/Episode 15.mkv", 1, 15))
    }

    // =============================================================================================
    // The explicit tier
    // =============================================================================================

    @Test
    fun `explicit forms still match`() {
        assertTrue(m("Law.and.Order.S07E10.WEB.mkv", 7, 10))
        assertTrue(m("Law and Order 7x10 Family Business.mkv", 7, 10))
        assertTrue(m("Law and Order - Season 7 Episode 10.mkv", 7, 10))
        assertTrue(m("show.s7.e10.mkv", 7, 10))
        assertTrue(m("Show.Season.1.Episode.15.mkv", 1, 15))
        assertTrue(m("Show/S01/E15.mkv", 1, 15))
    }

    @Test
    fun `explicit forms do not cross-match`() {
        assertFalse(m("Law.and.Order.S07E11.WEB.mkv", 7, 10))
        assertFalse(m("Law.and.Order.S08E10.WEB.mkv", 7, 10))
    }

    @Test
    fun `Ep and EP prefixes are matchable`() {
        // "e0*15" needs the digits directly after the `e`; the P broke it, so "S01EP15" simply missed
        // even though it is common enough that Sonarr carries dedicated patterns for it.
        assertTrue(m("Show.S01EP15.mkv", 1, 15))
        assertTrue(m("Show.S01.Ep15.mkv", 1, 15))
        assertTrue(m("Show.S1.Ep.15.mkv", 1, 15))
        assertTrue(m("Title S01 - EP14.mkv", 1, 14))
        assertFalse(m("Show.S01EP15.mkv", 1, 16))
    }

    @Test
    fun `disc numbered and localized markers match`() {
        assertTrue(m("Show.S01D02.E15.mkv", 1, 15))
        assertTrue(m("Show.Se.01.afl.15.mkv", 1, 15))
        assertTrue(m("Show.Temporada 1 Capitulo 15.mkv", 1, 15))
        assertTrue(m("Show.S01.Folge.15.mkv", 1, 15))
        assertTrue(m("Show.S01.odc.15.mkv", 1, 15))
    }

    @Test
    fun `a repeated code and a split part still match`() {
        assertTrue(m("Show.S01E05.S01E06.mkv", 1, 6))
        assertTrue(m("Show.S01E05a.mkv", 1, 5))
    }

    // =============================================================================================
    // resolve() — pack-wide arbitration
    // =============================================================================================

    @Test
    fun `indexOf finds the requested episode in a pack and skips non-video`() {
        val names = listOf(
            "readme.txt",
            "701 - Causa Mortis.avi",
            "702 - ID.avi",
            "710 - Family Business.avi",
        )
        val playable = { n: String -> n.endsWith(".avi") }
        assertEquals(3, EpisodeFileMatcher.indexOf(names, 7, 10, playable))
        assertEquals(1, EpisodeFileMatcher.indexOf(names, 7, 1, playable))
    }

    @Test
    fun `indexOf returns null when the episode is genuinely absent`() {
        val names = listOf("701 - Causa Mortis.avi", "702 - ID.avi")
        assertEquals(null, EpisodeFileMatcher.indexOf(names, 7, 10) { true })
        // ...and when the caller has no episode in mind at all.
        assertEquals(null, EpisodeFileMatcher.indexOf(names, null, null) { true })
    }

    @Test
    fun `a lone codec or year token cannot out-vote a pack`() {
        // One file carrying a 3-4 digit number proves nothing. Corroboration - a MAJORITY of the pack
        // sharing the shape, with no two files claiming the same episode - is what makes compact safe.
        val names = listOf(
            "Show.Pilot.WEB-DL.x264-GRP.mkv",
            "Show.Second.WEB-DL.x264-GRP.mkv",
            "Show.Third.WEB-DL.x264-GRP.mkv",
        )
        // every file claims S02E64 -> duplicates -> the whole compact tier is distrusted
        assertTrue(resolve(names, 2, 64) is Match.None)
    }

    @Test
    fun `an exact match beats a weak compact hit later in the pack`() {
        // firstOrNull over one mixed scan let a weak compact false positive on file 0 win over an exact
        // SxxExx match on file 19. Tiers make that impossible.
        val names = listOf(
            "Show.junk.0720.sample-notes.mkv",
            "Show.S07.E20.1080p.mkv",
        )
        assertEquals(1, idx(names, 7, 20))
    }

    @Test
    fun `two files claiming the same episode are reported as ambiguous, never picked`() {
        val names = listOf("Show.S01E15.ENG.mkv", "Show.S01E15.JPN.mkv")
        val r = resolve(names, 1, 15)
        assertTrue(r is Match.Ambiguous)
        assertEquals(listOf(0, 1), (r as Match.Ambiguous).indices)
        assertEquals(null, idx(names, 1, 15))    // indexOf refuses to collapse a tie
    }

    @Test
    fun `a single episode file wins over a double that merely contains the episode`() {
        // Both hold episode 15, but the double STARTS an episode early.
        val names = listOf("Show.S01E14E15.mkv", "Show.S01E15.mkv")
        assertEquals(1, idx(names, 1, 15))
        assertEquals(0, idx(names, 1, 14))
    }

    @Test
    fun `extras and specials are never chosen for a requested episode`() {
        val names = listOf(
            "Show S01/Extras/Show.S01E15.Behind.The.Scenes.mkv",
            "Show S01/Show.S01E15.mkv",
        )
        assertEquals(1, idx(names, 1, 15))
        // A special is only reachable when season 0 is what was asked for.
        val withSpecial = listOf("Show/Specials/Show.S00E15.mkv", "Show/Show.S01E15.mkv")
        assertEquals(1, idx(withSpecial, 1, 15))
        assertEquals(0, idx(withSpecial, 0, 15))
    }

    // =============================================================================================
    // Tier 3 — season from the FOLDER, episode-only in the file name
    // =============================================================================================

    @Test
    fun `folder season plus a leading bare number resolves`() {
        val names = (1..25).map { "Octonauts Season 1/%02d - Episode Title.mkv".format(it) }
        assertEquals(14, idx(names, 1, 15))
        assertEquals(0, idx(names, 1, 1))
        assertEquals(null, idx(names, 1, 26))
        // ...and the folder's season is binding: this pack is not season 2.
        assertEquals(null, idx(names, 2, 15))
    }

    @Test
    fun `iTunes numbering resolves`() {
        val names = (1..13).map { "Show/Season 1/1-%02d Title (HD).m4v".format(it) }
        assertEquals(4, idx(names, 1, 5))
        assertEquals(null, idx(names, 2, 5))
    }

    @Test
    fun `a multi season pack is judged against the requested season's own folder`() {
        val names = (1..5).flatMap { s -> (1..12).map { e -> "Show S01-S05 Complete/Season $s/%02d - Title.mkv".format(e) } }
        assertEquals(2 * 12 + 6, idx(names, 3, 7))
        assertEquals(0 * 12 + 0, idx(names, 1, 1))
    }

    @Test
    fun `a dash-numbered pack resolves when the folder proves the season`() {
        val names = (1..12).map { "[Judas] Show (Season 1) [1080p]/[Judas] Show - %02d.mkv".format(it) }
        assertEquals(4, idx(names, 1, 5))
    }

    @Test
    fun `absolute numbering with no season anywhere is refused past season one`() {
        // Absolute 15 is S02E02 of a 13-episode season 1, NOT S01E15, and this file has no episode-count
        // table to convert with. Season 1 is the one case where the two numberings coincide.
        val names = (1..25).map { "[SubsPlease] Show - %02d (1080p) [ABCD1234].mkv".format(it) }
        assertEquals(14, idx(names, 1, 15))
        assertEquals(null, idx(names, 2, 15))
        assertEquals(null, idx(names, 3, 2))
    }

    @Test
    fun `an inconsistent pack is refused rather than guessed`() {
        // Mixed shapes: no single convention explains every file, so there is no consistent numbering
        // to trust and the caller gets an honest failure.
        val names = listOf(
            "Show Season 1/01 - One.mkv",
            "Show Season 1/Two.mkv",
            "Show Season 1/Ep 03 - Three.mkv",
            "Show Season 1/15 - Fifteen.mkv",
        )
        assertTrue(resolve(names, 1, 15) is Match.None)
    }

    @Test
    fun `duplicate numbering under one shape is refused`() {
        val names = listOf(
            "Show Season 1/15 - Part A.mkv",
            "Show Season 1/15 - Part B.mkv",
            "Show Season 1/16 - Next.mkv",
        )
        assertTrue(resolve(names, 1, 15) !is Match.Unique)
    }

    @Test
    fun `numbering that does not start near one is refused`() {
        // A run of 101..113 is a compact/absolute artefact, not per-season episode numbers.
        val names = (101..113).map { "Show Season 1/$it Title.mkv" }
        assertTrue(resolve(names, 1, 105) is Match.None)
    }

    @Test
    fun `Ep-prefixed files resolve when the folder carries the season`() {
        val names = (1..20).map { "Show/S1/Show Ep%02d.mkv".format(it) }
        assertEquals(14, idx(names, 1, 15))
    }

    // =============================================================================================
    // Guards on the public surface
    // =============================================================================================

    @Test
    fun `negative and null inputs are inert`() {
        assertFalse(m("Show.S01E15.mkv", -1, 15))
        assertFalse(m("Show.S01E15.mkv", 1, -1))
        assertEquals(null, idx(listOf("Show.S01E15.mkv"), null, 15))
        assertEquals(null, idx(listOf("Show.S01E15.mkv"), 1, null))
        assertTrue(resolve(emptyList(), 1, 15) is Match.None)
    }

    // =============================================================================================
    // RUN_TAIL boundary — a token glued to the episode number is not a range endpoint
    // =============================================================================================

    @Test
    fun `a bit-depth or audio-layout suffix is never a range endpoint`() {
        // The dash branch used to refuse only a following digit / x / p / i, so any OTHER letter was
        // accepted and the run was INTERPOLATED: "S01E12-10bit" parsed as episodes 10, 11 and 12, and
        // "S01E10-5.1" as 5 through 10. One glued token manufactured a whole block of episodes the file
        // does not contain — files claiming episodes they lack is exactly how the wrong one gets played.
        assertFalse(m("Show.S01E12-10bit.1080p.mkv", 1, 10))
        assertFalse(m("Show.S01E12-10bit.1080p.mkv", 1, 11))
        assertTrue(m("Show.S01E12-10bit.1080p.mkv", 1, 12))

        assertFalse(m("Show.S01E12-8bit.mkv", 1, 8))
        assertTrue(m("Show.S01E12-8bit.mkv", 1, 12))

        assertFalse(m("Show.S01E10-5.1.mkv", 1, 5))
        assertTrue(m("Show.S01E10-5.1.mkv", 1, 10))

        assertFalse(m("Show.S01E05-4K.mkv", 1, 4))
        assertTrue(m("Show.S01E05-4K.mkv", 1, 5))
    }

    @Test
    fun `genuine combined forms survive the tighter boundary`() {
        // The guard must not cost the Octonauts fix: every real range form still parses.
        assertTrue(m("Show.S01E14E15.mkv", 1, 15))
        assertTrue(m("Show.S02E01-E03.mkv", 2, 2))
        assertTrue(m("Show.S02E01-03.mkv", 2, 3))
        assertTrue(m("Show.1x15-16.mkv", 1, 16))
    }

    @Test
    fun `a folder stating a range does not lend it to every file inside`() {
        // A batch-drop root describes the TORRENT. Expanding it attributed 1, 2 and 3 to every file, so
        // every file "claimed" whatever was asked for; the tie could not be broken and the caller took
        // the largest — the wrong episode, with nothing on screen to say so.
        val names = listOf(
            "Show.S04E01-E03.1080p-GRP/Show.S04E01.mkv",
            "Show.S04E01-E03.1080p-GRP/Show.S04E02.mkv",
            "Show.S04E01-E03.1080p-GRP/Show.S04E03.mkv",
        )
        assertEquals(1, idx(names, 4, 2))
        assertEquals(2, idx(names, 4, 3))
    }
}
