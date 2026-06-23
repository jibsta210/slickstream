package com.slickstream.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** SlickStream brand palette — cinematic near-black with electric violet + cyan accents. */
object Brand {
    val Violet = Color(0xFF7C5CFF)
    val VioletDim = Color(0xFF5B43C9)
    val Cyan = Color(0xFF00E0C6)
    val Background = Color(0xFF0B0B0F)
    val Surface = Color(0xFF15151C)
    val SurfaceVariant = Color(0xFF1F1F2A)
    val OnSurface = Color(0xFFECECF2)
    val OnSurfaceDim = Color(0xFF9A9AA8)
    val Error = Color(0xFFFF5470)
    val Star = Color(0xFFFFC857)
}

private val SlickDarkColors = darkColorScheme(
    primary = Brand.Violet,
    onPrimary = Color.White,
    secondary = Brand.Cyan,
    onSecondary = Color(0xFF00201C),
    background = Brand.Background,
    onBackground = Brand.OnSurface,
    surface = Brand.Surface,
    onSurface = Brand.OnSurface,
    surfaceVariant = Brand.SurfaceVariant,
    onSurfaceVariant = Brand.OnSurfaceDim,
    error = Brand.Error,
    outline = Color(0xFF34343F),
)

val SlickTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
)

/**
 * App theme. Always dark (cinematic). Used by BOTH phone and TV composables — TV screens that
 * use androidx.tv.material3 components wrap their own tv MaterialTheme internally where needed.
 */
@Composable
fun SlickStreamTheme(
    darkTheme: Boolean = true || isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SlickDarkColors,
        typography = SlickTypography,
        content = content,
    )
}
