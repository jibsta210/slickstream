package com.slickstream.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.slickstream.ui.theme.Brand

/**
 * A focusable, brand-styled text field for on-screen typing on TV. Focusing it (D-pad) brings up
 * the platform on-screen keyboard; the IME "Search" action triggers [onSubmit].
 */
@Composable
fun TvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Brand.Surface, shape)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Brand.Violet else Brand.SurfaceVariant,
                shape = shape,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = Brand.OnSurfaceDim,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = "Search movies & shows",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Brand.OnSurfaceDim,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    interactionSource = interaction,
                    singleLine = true,
                    cursorBrush = SolidColor(Brand.Cyan),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Brand.OnSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
