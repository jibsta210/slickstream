package com.slickstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.slickstream.ui.theme.Brand

/**
 * Title above a content row / section. A short brand-gradient accent bar sits to the
 * left of the text for a cinematic, premium feel.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .width(4.dp)
                .height(20.dp)
                .background(
                    brush = Brush.verticalGradient(listOf(Brand.Violet, Brand.Cyan)),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Brand.OnSurface,
        )
    }
}
