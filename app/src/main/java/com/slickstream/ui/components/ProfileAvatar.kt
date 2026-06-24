package com.slickstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slickstream.ui.theme.Brand

/**
 * The avatar tint palette, derived from the Brand accents. A profile's [Profile.colorIndex] picks
 * one of these (wrapping with modulo), so avatars stay on-brand and visually distinct.
 */
val ProfileAvatarColors: List<Color> = listOf(
    Brand.Violet,
    Brand.Cyan,
    Color(0xFFFF7AA8), // rose
    Brand.Star,        // amber
    Color(0xFF5EC8FF), // sky
    Color(0xFF8BE08A), // mint
)

/** How many distinct avatar tints exist — used to cycle the palette when adding a profile. */
val profileAvatarColorCount: Int get() = ProfileAvatarColors.size

/** Resolve a profile colour from its index, wrapping safely for any (even negative) index. */
fun profileAvatarColor(colorIndex: Int): Color {
    val n = ProfileAvatarColors.size
    return ProfileAvatarColors[((colorIndex % n) + n) % n]
}

/**
 * A circular profile avatar: a Brand-palette tint chosen by [colorIndex], the [name]'s first
 * letter (uppercase) centred in white, and — for a kids profile ([isKids]) — a small star badge
 * in the bottom-right corner as a subtle, friendly treatment.
 *
 * Used on BOTH phone and TV (pure Compose foundation, no form-factor-specific components).
 */
@Composable
fun ProfileAvatar(
    name: String,
    colorIndex: Int,
    isKids: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val tint = profileAvatarColor(colorIndex)
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(tint, tint.copy(alpha = 0.78f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }

        if (isKids) {
            // Small star badge bottom-right — the friendly "kids" marker.
            val badge = size * 0.34f
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .clip(CircleShape)
                    .background(Brand.Star),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = "Kids profile",
                    tint = Color(0xFF2A1E00),
                    modifier = Modifier.size(badge * 0.72f),
                )
            }
        }
    }
}
