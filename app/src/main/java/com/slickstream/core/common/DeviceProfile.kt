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
}
