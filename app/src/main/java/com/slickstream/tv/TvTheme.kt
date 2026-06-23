package com.slickstream.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.slickstream.ui.theme.Brand

/**
 * TV-flavoured MaterialTheme. The Compose-for-TV component set lives in `androidx.tv.material3`
 * and has its own `MaterialTheme` / color scheme types, so we re-express the SlickStream brand
 * palette here. Phone screens keep using [com.slickstream.ui.theme.SlickStreamTheme]; TV screens
 * wrap themselves in this so tv Card / Button / Carousel pick up the brand colours + glow.
 */
private val TvColors = darkColorScheme(
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
    border = Color(0xFF34343F),
    error = Brand.Error,
)

/** Slightly larger type scale tuned for 10-foot legibility (tv-material3 Typography). */
private val TvTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 50.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

/** Wrap TV content in the brand-themed `androidx.tv.material3.MaterialTheme`. */
@Composable
fun SlickStreamTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvColors,
        typography = TvTypography,
        content = content,
    )
}
