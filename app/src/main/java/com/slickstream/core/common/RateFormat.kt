package com.slickstream.core.common

/**
 * One transfer-rate formatter for the whole app.
 *
 * There were three: the phone player rolled over to "MB/s", the TV rebuffer badge rolled over to
 * "MB/s", and the TV startup overlay did not — so the same 7.7 MB/s stream rendered as "7900 KB/s" on
 * one screen of the same app and "7.7 MB/s" on another. Two surfaces disagreeing about the UNIT of the
 * same field is the same class of bug as two surfaces disagreeing about its value.
 */
fun formatRate(bytesPerSec: Int): String = when {
    bytesPerSec <= 0 -> "0 KB/s"
    bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec / (1024f * 1024f))
    else -> "${bytesPerSec / 1024} KB/s"
}
