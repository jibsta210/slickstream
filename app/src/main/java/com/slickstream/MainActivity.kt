package com.slickstream

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.slickstream.core.common.DeviceProfile
import com.slickstream.feature.update.UpdateGate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.data.settings.AppSettings
import com.slickstream.data.settings.ScreenCalibration
import com.slickstream.data.settings.SettingsRepository
import com.slickstream.feature.player.LocalPipController
import com.slickstream.feature.player.PipController
import com.slickstream.tv.TvApp
import com.slickstream.ui.PhoneApp
import com.slickstream.ui.theme.SlickStreamTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single entry point for both form factors. Detects Android TV at runtime → [TvApp]; everything
 * else gets the touch-first [PhoneApp]. Applies the user's display-density scale app-wide, and
 * hosts Picture-in-Picture for the phone player via [PipController].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var deviceProfile: DeviceProfile

    // Authoritative TV signal (LEANBACK feature OR-ed with TV ui-mode); see DeviceProfile.
    private val onTv: Boolean get() = deviceProfile.isTv

    // PiP state, driven by the player screen through [pipController].
    private var pipEnabled = false
    private var pipAspect = 16f / 9f
    private var pipSourceHint: Rect? = null
    private val inPipState = mutableStateOf(false)

    private val pipController = object : PipController {
        override val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !onTv &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        override val isInPip: State<Boolean> = inPipState

        override fun update(enabled: Boolean, aspect: Float, sourceHint: Rect?) {
            pipEnabled = enabled && isSupported
            pipAspect = aspect
            pipSourceHint = sourceHint
            // API 31+ uses auto-enter; keep the params fresh so the system can enter on home-press.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isSupported) {
                runCatching { setPictureInPictureParams(buildPipParams()) }
            }
        }

        override fun enter() {
            if (!pipEnabled) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { enterPictureInPictureMode(buildPipParams()) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        // Android requires the PiP aspect ratio within ~[0.42, 2.39]; clamp to be safe.
        val ratio = pipAspect.coerceIn(0.5f, 2.38f)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000f).toInt().coerceAtLeast(1), 1000))
        pipSourceHint?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pipEnabled).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 26–30 have no auto-enter; enter PiP on home-press if the player wants it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) pipController.enter()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipState.value = isInPictureInPictureMode
    }

    /**
     * Android TV: go genuinely immersive — edge-to-edge alone draws BEHIND the system bars but doesn't
     * remove them, so a panel that reports a (sometimes asymmetric) system-bar/safe-area inset can leave
     * a black gutter on one side. Hiding the bars hands the app the whole panel. TVs drop immersive on
     * focus changes, so this is re-asserted from [onWindowFocusChanged]. Phones keep their bars (the UI
     * pads for them); the phone player hides them itself only during fullscreen playback.
     */
    private fun applyTvImmersiveIfNeeded() {
        if (!onTv) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyTvImmersiveIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyTvImmersiveIfNeeded()
        setContent {
            val settings by settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            // Live override (in-memory, instant) wins while the user is on the calibration screen;
            // otherwise the persisted values drive the fit. null = no override active.
            val liveCalibration by settingsRepository.liveCalibration
                .collectAsStateWithLifecycle()
            val calibration = liveCalibration ?: ScreenCalibration(
                scale = settings.screenScale,
                offsetX = settings.screenOffsetX,
                offsetY = settings.screenOffsetY,
            )
            val base = LocalDensity.current
            CompositionLocalProvider(
                // Scale dp (and therefore sp) uniformly — a true "DPI" change.
                LocalDensity provides Density(base.density * settings.density.scale, base.fontScale),
                LocalPipController provides pipController,
            ) {
                SlickStreamTheme {
                    Box(Modifier.fillMaxSize()) {
                        // Screen calibration fits the whole app (UI + video) to the user's TV.
                        com.slickstream.ui.ScreenCalibrated(
                            scale = calibration.scale,
                            offsetX = calibration.offsetX,
                            offsetY = calibration.offsetY,
                        ) {
                            if (onTv) TvApp() else PhoneApp()
                        }
                        // Overlays both shells; checks for a self-hosted update on launch.
                        UpdateGate()
                    }
                }
            }
        }
    }
}
