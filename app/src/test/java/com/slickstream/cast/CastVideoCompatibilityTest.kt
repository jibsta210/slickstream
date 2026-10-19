package com.slickstream.cast

import org.junit.Assert.*
import org.junit.Test

class CastVideoCompatibilityTest {
    private val h264 = CastVideoFormat("h264", 100, 41, 1920, 1080, 24000, 1001)

    @Test fun `ordinary 1080p H264 retains its video without encoding`() {
        assertTrue(h264.canCopy)
        assertTrue(h264.copy(profile = 578).canCopy)
    }

    @Test fun `HEVC and other codecs need video conversion even if receiver accepts audio`() {
        listOf("hevc", "h265", "vp09", "av01", "").forEach { assertFalse(h264.copy(codec = it).canCopy) }
    }

    @Test fun `H264 outside baseline Chromecast limits must be converted`() {
        listOf(h264.copy(profile = 110), h264.copy(level = 51), h264.copy(width = 3840),
            h264.copy(height = 2160), h264.copy(frameRateNum = 60, frameRateDen = 1),
            h264.copy(profile = -1), h264.copy(level = 0), h264.copy(width = 0),
            h264.copy(frameRateDen = 0)).forEach { assertFalse(it.canCopy) }
    }
}
