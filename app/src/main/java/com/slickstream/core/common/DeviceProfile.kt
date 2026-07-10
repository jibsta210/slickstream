package com.slickstream.core.common

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Form-factor + capability detection, computed once and shared (Hilt @Singleton).
 *
 * [isTv] decides the phone-vs-TV shell. It treats the LEANBACK system feature as the
 * AUTHORITATIVE TV signal — UiModeManager.currentModeType is unreliable on many Google TV /
 * operator boxes (they report NORMAL/UNDEFINED), which previously made the app render the phone
 * UI on a TV. The TV ui-mode is kept only as a secondary hint.
 *
 * [isLowPower] additionally folds in system-flagged low-RAM devices, and gates the conservative
 * torrent + player profile that keeps a weak SoC from freezing the whole device during streaming.
 */
@Singleton
class DeviceProfile @Inject constructor(
    @ApplicationContext context: Context,
) {
    val isTv: Boolean = run {
        val pm = context.packageManager
        val leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.software.leanback_only") ||
            pm.hasSystemFeature("android.hardware.type.television")
        val uiTv = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        leanback || uiTv
    }

    val isLowPower: Boolean = run {
        val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true
        isTv || lowRam
    }

    /**
     * The highest quality TIER this display can actually show (matches QualityPreference tiers:
     * 4 = 2160p-class panel, 3 = 1080p, 2 = 720p, 1 = below). There's no point pulling a 4K source on
     * a 1080p panel — bigger file, slower start, and on many TV boxes an undecodable HEVC Main10
     * profile that ends in a black screen. The pickers clamp every quality preference to this.
     *
     * Read from the display's SUPPORTED MODES, not window metrics — TV boxes commonly render the app
     * UI at 1080p while the panel (and the video pipeline) is genuinely 4K, so metrics under-report.
     * Falls back to metrics if modes are unavailable.
     */
    val maxDisplayTier: Int = run {
        val maxHeight = runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?.supportedModes
                ?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) }
        }.getOrNull()
            ?: context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
        when {
            maxHeight >= 2000 -> 4  // 2160p-class
            maxHeight >= 1000 -> 3  // 1080p (also the right cap for 1440p phones — no 1440p tier)
            maxHeight >= 700 -> 2   // 720p
            else -> 1
        }
    }
}
