package com.slickstream.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeFileMatcherTest {

    private fun m(path: String, s: Int, e: Int) = EpisodeFileMatcher.matches(path, s, e)

    // --- the reported failure -----------------------------------------------------------------------

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

    // --- the thing that makes compact matching dangerous ---------------------------------------------

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

    // --- the explicit forms must keep working --------------------------------------------------------

    @Test
    fun `explicit forms still match`() {
        assertTrue(m("Law.and.Order.S07E10.WEB.mkv", 7, 10))
        assertTrue(m("Law and Order 7x10 Family Business.mkv", 7, 10))
        assertTrue(m("Law and Order - Season 7 Episode 10.mkv", 7, 10))
        assertTrue(m("show.s7.e10.mkv", 7, 10))
    }

    @Test
    fun `explicit forms do not cross-match`() {
        assertFalse(m("Law.and.Order.S07E11.WEB.mkv", 7, 10))
        assertFalse(m("Law.and.Order.S08E10.WEB.mkv", 7, 10))
    }

    // --- indexOf ------------------------------------------------------------------------------------

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
}
