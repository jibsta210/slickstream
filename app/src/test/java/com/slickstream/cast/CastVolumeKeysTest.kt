package com.slickstream.cast

import android.view.KeyEvent.*
import org.junit.Assert.*
import org.junit.Test

class CastVolumeKeysTest {
    private var time = 0L
    private val keys = CastVolumeKeys { time }
    private val receiver = Any()
    private val levels = mutableListOf<Float>()
    private var mutes = 0
    private fun send(code: Int, action: Int = ACTION_DOWN, repeat: Int = 0,
                     target: Any? = receiver, volume: Float = 0.5f) =
        keys.handle(code, action, repeat, target, volume, levels::add, { mutes++ })

    @Test fun localPlaybackAndUnrelatedKeysPassThrough() {
        assertFalse(send(KEYCODE_VOLUME_UP, target = null))
        assertFalse(send(KEYCODE_VOLUME_UP, ACTION_UP, target = null))
        assertFalse(send(KEYCODE_DPAD_UP))
        assertTrue(levels.isEmpty())
    }

    @Test fun rapidTapsAndHeldKeysAccumulateDespiteDelayedReceiverStatus() {
        assertTrue(send(KEYCODE_VOLUME_UP))
        assertTrue(send(KEYCODE_VOLUME_UP, ACTION_UP))
        assertTrue(send(KEYCODE_VOLUME_UP))
        assertTrue(send(KEYCODE_VOLUME_UP, repeat = 1))
        assertEquals(3, levels.size)
        assertEquals(0.65f, levels.last(), 0.0001f)
        assertTrue(send(KEYCODE_VOLUME_UP, ACTION_UP))
        assertEquals(3, levels.size)
    }

    @Test fun limitsAndExternalVolumeChangesAreRespected() {
        send(KEYCODE_VOLUME_UP, volume = 0.99f)
        assertEquals(1f, levels.last(), 0f)
        time += 1_001
        send(KEYCODE_VOLUME_DOWN, volume = 0.01f)
        assertEquals(0f, levels.last(), 0f)
        time += 1_001
        send(KEYCODE_VOLUME_UP, volume = 0.2f)
        assertEquals(0.25f, levels.last(), 0.0001f)
    }

    @Test fun disconnectConsumesRestOfGestureWithoutChangingPhoneVolume() {
        send(KEYCODE_VOLUME_DOWN)
        assertTrue(send(KEYCODE_VOLUME_DOWN, repeat = 1, target = null))
        assertTrue(send(KEYCODE_VOLUME_DOWN, ACTION_UP, target = null))
        assertFalse(send(KEYCODE_VOLUME_DOWN, target = null))
        assertEquals(1, levels.size)
    }

    @Test fun switchingReceiverDiscardsPendingVolumeAndMuteDoesNotRepeat() {
        send(KEYCODE_VOLUME_UP)
        send(KEYCODE_VOLUME_UP, target = Any(), volume = 0.1f)
        assertEquals(0.15f, levels.last(), 0.0001f)
        send(KEYCODE_VOLUME_MUTE)
        send(KEYCODE_VOLUME_MUTE, repeat = 1)
        send(KEYCODE_VOLUME_MUTE, ACTION_UP)
        assertEquals(1, mutes)
    }

    @Test fun sliderChangeIsUsedByNextVolumeKey() {
        send(KEYCODE_VOLUME_DOWN)
        keys.volumeRequested(0.8f)
        send(KEYCODE_VOLUME_UP)
        assertEquals(0.85f, levels.last(), 0.0001f)
    }
}
