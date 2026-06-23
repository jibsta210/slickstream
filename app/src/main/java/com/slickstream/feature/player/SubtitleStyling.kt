package com.slickstream.feature.player

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.slickstream.data.settings.SubtitleSize
import com.slickstream.data.settings.SubtitleStyle

/** Map a [SubtitleStyle] preset to a Media3 [CaptionStyleCompat]. */
fun captionStyleFor(style: SubtitleStyle): CaptionStyleCompat = when (style) {
    SubtitleStyle.DROP_SHADOW -> CaptionStyleCompat(
        Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null,
    )
    SubtitleStyle.OUTLINE -> CaptionStyleCompat(
        Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
    )
    SubtitleStyle.BLACK_BOX -> CaptionStyleCompat(
        Color.WHITE, 0xCC000000.toInt(), Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, null,
    )
    SubtitleStyle.YELLOW -> CaptionStyleCompat(
        0xFFFFEB3B.toInt(), Color.TRANSPARENT, Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null,
    )
}

/** Apply the user's size + style to a player's [SubtitleView], overriding any embedded styles. */
fun SubtitleView.applyAppearance(size: SubtitleSize, style: SubtitleStyle) {
    setApplyEmbeddedStyles(false)
    setApplyEmbeddedFontSizes(false)
    setFractionalTextSize(size.fraction)
    setStyle(captionStyleFor(style))
}
