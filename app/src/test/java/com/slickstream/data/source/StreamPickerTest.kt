package com.slickstream.data.source

import com.slickstream.core.model.StreamSource
import com.slickstream.data.settings.StreamSizePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPickerTest {

    @Test
    fun healthyPackBeatsDeadSingleEpisode() {
        val deadSingle = source(hash = "a", seeders = 1, isPack = false, size = 400)
        val healthyPack = source(hash = "b", seeders = 100, isPack = true, size = 800)

        val picked = StreamPicker.pick(
            listOf(deadSingle, healthyPack),
            maxTier = 3,
            sizePref = StreamSizePreference.SMALLEST,
            lowPower = false,
        )

        assertEquals(healthyPack, picked)
    }

    @Test
    fun thinPackBeatsEvenThinnerSingleEpisodeBelowAbsoluteFloor() {
        val nearlyDeadSingle = source(hash = "a", seeders = 1, isPack = false, size = 400)
        val thinPack = source(hash = "b", seeders = 5, isPack = true, size = 800)

        val picked = StreamPicker.pick(
            listOf(nearlyDeadSingle, thinPack),
            maxTier = 3,
            sizePref = StreamSizePreference.SMALLEST,
            lowPower = false,
        )

        assertEquals(thinPack, picked)
    }

    @Test
    fun fileIndexAloneDoesNotMakeReleaseAPack() {
        assertFalse(StreamPicker.looksLikePack("Movie.2020.1080p.WEB-DL.mkv", fileIndex = 2))
        assertTrue(StreamPicker.looksLikePack("Show S01 Complete 1080p", fileIndex = 0))
        assertTrue(StreamPicker.looksLikePack("Show.S01.1080p.BluRay", fileIndex = null))
    }

    @Test
    fun directPickerHonorsQualityCap() {
        val direct4k = source(hash = "4", seeders = 0, quality = "4K", direct = "https://cdn/4k")
        val direct1080 = source(hash = "1", seeders = 0, quality = "1080p", direct = "https://cdn/1080")

        assertEquals(
            direct1080,
            StreamPicker.pickDirect(listOf(direct4k, direct1080), maxTier = 3, lowPower = false),
        )
    }

    @Test
    fun resumeRejects4kSavedOnLowPowerTvWhen1080Exists() {
        val saved4k = source(hash = "4", seeders = 80, quality = "4K")
        val safe1080 = source(hash = "1", seeders = 60, quality = "1080p")

        assertFalse(
            StreamPicker.isResumeCompatible(
                source = saved4k,
                candidates = listOf(saved4k, safe1080),
                maxTier = Int.MAX_VALUE,
                lowPower = true,
            ),
        )
    }

    @Test
    fun resumeAcceptsCompatible1080SavedOnLowPowerTv() {
        val saved1080 = source(hash = "1", seeders = 60, quality = "1080p")
        val other720 = source(hash = "7", seeders = 80, quality = "720p")

        assertTrue(
            StreamPicker.isResumeCompatible(
                source = saved1080,
                candidates = listOf(saved1080, other720),
                maxTier = Int.MAX_VALUE,
                lowPower = true,
            ),
        )
    }

    @Test
    fun resumeRejectsUnplayableOrCamWhenSafeAlternativeExists() {
        val safe = source(hash = "safe", seeders = 40)
        val unplayable = source(hash = "avi", seeders = 80, playable = false)
        val cam = source(hash = "cam", seeders = 100, isCam = true)
        val list = listOf(unplayable, cam, safe)

        assertFalse(StreamPicker.isResumeCompatible(unplayable, list, maxTier = 3, lowPower = false))
        assertFalse(StreamPicker.isResumeCompatible(cam, list, maxTier = 3, lowPower = false))
        assertTrue(StreamPicker.isResumeCompatible(safe, list, maxTier = 3, lowPower = false))
    }

    // --- Real-Debrid / direct must always beat a torrent -------------------------------------------

    @Test
    fun `a direct RD stream is chosen over healthier torrents`() {
        // The reported regression: "they all say RD available but now choose torrents". A direct source
        // plays instantly with no swarm, so it must win regardless of how well-seeded the torrents are.
        val rd = source(hash = "rd", seeders = 0, direct = "https://cdn.real-debrid.com/d/XYZ/Movie.mkv")
        val fatTorrent = source(hash = "t1", seeders = 900, size = 2000)
        val healthyTorrent = source(hash = "t2", seeders = 400, size = 900)

        val picked = StreamPicker.pickDirect(
            listOf(fatTorrent, rd, healthyTorrent), maxTier = 3, lowPower = false,
        )
        assertEquals(rd, picked)
    }

    @Test
    fun `an RD stream whose name has no container tag is still chosen`() {
        // The mkv preference added for startup speed must NEVER cost us a direct source: RD rows are
        // routinely named without a container ("[RD+] Movie.2024.1080p.WEB-DL"), so a container filter
        // applied to the direct path would silently demote every one of them to a torrent.
        val rdNoContainer = source(hash = "rd", seeders = 0, direct = "https://cdn.real-debrid.com/d/A/f")
        val mkvTorrent = source(hash = "t", seeders = 500).copy(frontIndexContainer = true)

        assertEquals(rdNoContainer, StreamPicker.pickDirect(listOf(mkvTorrent, rdNoContainer), 3, false))
    }

    @Test
    fun `the mkv preference never removes the only playable candidate`() {
        // Soft-preference contract: if nothing is an mkv, the pick must fall through, not return null.
        val mp4a = source(hash = "a", seeders = 100)
        val mp4b = source(hash = "b", seeders = 300)
        val picked = StreamPicker.pick(listOf(mp4a, mp4b), 3, StreamSizePreference.BALANCED, false)
        assertEquals(mp4b, picked)
    }

    @Test
    fun `an mkv is preferred only among equally healthy torrents`() {
        // ...and must not override swarm health: a 5-seed mkv must not beat a 500-seed mp4.
        val deadMkv = source(hash = "mkv", seeders = 5).copy(frontIndexContainer = true)
        val healthyMp4 = source(hash = "mp4", seeders = 500)
        val picked = StreamPicker.pick(listOf(deadMkv, healthyMp4), 3, StreamSizePreference.BALANCED, false)
        assertEquals(healthyMp4, picked)
    }

    // --- Multi-audio detection -----------------------------------------------------------------

    @Test
    fun `MULTI and DUAL AUDIO releases are flagged as multi-audio`() {
        assertTrue(StreamPicker.looksMultiAudio("Mutiny.2026.1080p.WEB-DL.MULTi.DDP5.1.H264-GROUP"))
        assertTrue(StreamPicker.looksMultiAudio("Some.Show.S01E01.720p.BluRay.Dual.Audio.x264"))
        assertTrue(StreamPicker.looksMultiAudio("Film.2024.1080p.BluRay.VFF.x264"))
    }

    @Test
    fun `a plain WEB-DL is not multi-audio`() {
        // The German scene's "DL" (Dual Language) tag would match the "DL" in "WEB-DL" — the hyphen is
        // a word boundary — and mark essentially every modern release as multi-audio, permanently
        // disabling the player's container-default fallback. It is deliberately not in the pattern.
        assertFalse(StreamPicker.looksMultiAudio("Mutiny.2026.1080p.WEB-DL.DDP5.1.H264-GROUP"))
        assertFalse(StreamPicker.looksMultiAudio("Movie.2023.2160p.AMZN.WEB-DL.DDP5.1.HDR.H265"))
        assertFalse(StreamPicker.looksMultiAudio("Movie.1994.1080p.BluRay.x264-SPARKS"))
    }

    @Test
    fun `MULTI is not treated as a foreign tag - those releases usually carry english`() {
        // Deliberate: rejecting MULTI would throw away good sources. It only lowers the PLAYER's
        // confidence in the container default; it never rejects the source.
        assertTrue(StreamPicker.looksEnglish("Mutiny.2026.1080p.WEB-DL.MULTi.DDP5.1.H264", "Mutiny"))
    }

    private fun source(
        hash: String,
        seeders: Int,
        isPack: Boolean = false,
        size: Long = 700,
        quality: String = "1080p",
        direct: String? = null,
        playable: Boolean = true,
        isCam: Boolean = false,
    ) = StreamSource(
        title = "source-$hash",
        magnetUri = if (direct == null) "magnet:?xt=urn:btih:$hash" else "",
        infoHash = hash,
        quality = quality,
        sizeBytes = size,
        seeders = seeders,
        provider = "test",
        isPack = isPack,
        directUrl = direct,
        playable = playable,
        isCam = isCam,
    )
}
