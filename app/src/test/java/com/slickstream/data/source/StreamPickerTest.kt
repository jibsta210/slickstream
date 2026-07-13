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

    private fun source(
        hash: String,
        seeders: Int,
        isPack: Boolean = false,
        size: Long = 700,
        quality: String = "1080p",
        direct: String? = null,
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
    )
}
