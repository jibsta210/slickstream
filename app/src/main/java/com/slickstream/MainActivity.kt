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
import com.slickstream.feature.update.UpdateGate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.data.settings.AppSettings
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

    private val onTv: Boolean by lazy { isRunningOnTv(this) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val base = LocalDensity.current
            CompositionLocalProvider(
                // Scale dp (and therefore sp) uniformly — a true "DPI" change.
                LocalDensity provides Density(base.density * settings.density.scale, base.fontScale),
                LocalPipController provides pipController,
            ) {
                SlickStreamTheme {
                    Box(Modifier.fillMaxSize()) {
                        if (onTv) TvApp() else PhoneApp()
                        // Overlays both shells; checks for a self-hosted update on launch.
                        UpdateGate()
                    }
                }
            }
        }
    }
}

fun isRunningOnTv(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
