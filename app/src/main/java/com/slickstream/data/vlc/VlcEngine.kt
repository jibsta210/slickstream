package com.slickstream.data.vlc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.videolan.libvlc.LibVLC
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide holder for the single shared [LibVLC] instance.
 *
 * Creating a [LibVLC] is expensive (it spins up the native VLC core and loads
 * shared libraries), so we keep exactly one per process and hand it to every
 * [VlcPlayer]. The instance owns the native VLC engine; individual players only
 * own their own `MediaPlayer`/`Media` objects and must NOT release this.
 */
@Singleton
class VlcEngine @Inject constructor(@ApplicationContext private val context: Context) {

    val libVlc: LibVLC by lazy {
        // Reasonable streaming defaults. `--no-*` trims unused subsystems so the
        // core stays lean. Hardware decoding stays ON (configured per-Media via
        // Media.setHWDecoderEnabled in VlcPlayer).
        val opts = arrayListOf(
            "--network-caching=1500",
            "--no-stats",
            "--no-snapshot-preview",
            "--audio-time-stretch",
        )
        LibVLC(context, opts)
    }

    fun release() {
        runCatching { libVlc.release() }
    }
}
