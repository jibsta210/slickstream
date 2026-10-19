package com.slickstream.cast

import android.view.KeyEvent

/** Own the complete key gesture so a release never changes the phone volume after a disconnect. */
internal class CastVolumeKeys(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val pressed = mutableSetOf<Int>()
    private var target: Any? = null
    private var requested: Float? = null
    private var requestedAt = 0L

    fun volumeRequested(volume: Float) {
        requested = volume.coerceIn(0f, 1f)
        requestedAt = now()
    }

    fun handle(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        receiver: Any?,
        volume: Float,
        setVolume: (Float) -> Unit,
        toggleMute: () -> Unit,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            keyCode != KeyEvent.KEYCODE_VOLUME_MUTE) return false
        if (action == KeyEvent.ACTION_UP) return pressed.remove(keyCode)
        if (action != KeyEvent.ACTION_DOWN) return false
        if (receiver !== target) { target = receiver; requested = null }
        if (receiver == null) return keyCode in pressed
        pressed.add(keyCode)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (repeatCount == 0) toggleMute()
        } else {
            // Cast status arrives asynchronously. Accumulate rapid taps/holds instead of repeatedly
            // applying the same step to an old receiver status; later external changes win again.
            val base = requested?.takeIf { now() - requestedAt < 1_000 } ?: volume
            val level = (base + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f)
                .coerceIn(0f, 1f)
            volumeRequested(level)
            setVolume(level)
        }
        return true
    }
}
