package com.slickstream.cast

/** Conservative common denominator for video-capable Chromecasts, including first generation. */
internal data class CastVideoFormat(
    val codec: String,
    val profile: Int,
    val level: Int,
    val width: Int,
    val height: Int,
    val frameRateNum: Int,
    val frameRateDen: Int,
) {
    val canCopy: Boolean get() = codec.lowercase() in setOf("h264", "avc1") &&
        profile in setOf(66, 77, 100, 578) && level in 1..41 &&
        width in 1..1920 && height in 1..1080 &&
        frameRateNum > 0 && frameRateDen > 0 && frameRateNum.toDouble() / frameRateDen <= 30.0
}
