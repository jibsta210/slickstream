package com.slickstream.tv.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.slickstream.ui.components.ProfileAvatarColors
import com.slickstream.ui.components.profileAvatarColor
import com.slickstream.ui.components.profileAvatarEmoji
import com.slickstream.ui.components.profileAvatarOptionCount
import com.slickstream.ui.theme.Brand

/**
 * D-pad-focusable, brand-styled create/edit dialog for the TV profile picker — name field, a kids
 * toggle, and a colour picker. Never the native dialog look. Back press + scrim dismiss as cancel.
 *
 * The name field is a [BasicTextField] (tv-material3 has no TextField); it takes initial focus so
 * the on-screen keyboard / remote text entry is immediate.
 */
@Composable
fun TvProfileEditDialog(
    title: String,
    initialName: String,
    initialIsKids: Boolean,
    initialColorIndex: Int,
    confirmLabel: String,
    onSave: (name: String, isKids: Boolean, colorIndex: Int, avatarIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    initialAvatarIndex: Int = 0,
) {
    var name by remember { mutableStateOf(initialName) }
    var isKids by remember { mutableStateOf(initialIsKids) }
    var colorIndex by remember { mutableIntStateOf(initialColorIndex) }
    var avatarIndex by remember { mutableIntStateOf(initialAvatarIndex) }
    val nameFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { nameFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Brand.Surface),
                modifier = Modifier.width(600.dp),
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface)

                    // Name field
                    Text("Name", style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
                    Box(
                        modifier = Modifier
                            .border(2.dp, Brand.Violet, RoundedCornerShape(12.dp))
                            .background(Brand.SurfaceVariant, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it.take(24) },
                            singleLine = true,
                            textStyle = TextStyle(color = Brand.OnSurface, fontSize = 20.sp),
                            cursorBrush = SolidColor(Brand.Violet),
                            modifier = Modifier
                                .width(520.dp)
                                .focusRequester(nameFocus),
                        )
                    }

                    // Kids toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Kids profile", style = MaterialTheme.typography.titleMedium, color = Brand.OnSurface)
                            Text(
                                "A family-friendly home with kid-safe rows.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Brand.OnSurfaceDim,
                            )
                        }
                        Switch(checked = isKids, onCheckedChange = { isKids = it })
                    }

                    // Colour picker
                    Text("Colour", style = MaterialTheme.typography.titleMedium, color = Brand.OnSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProfileAvatarColors.forEachIndexed { index, _ ->
                            val normalized = ((colorIndex % ProfileAvatarColors.size) + ProfileAvatarColors.size) % ProfileAvatarColors.size
                            ColorSwatch(
                                color = profileAvatarColor(index),
                                selected = index == normalized,
                                onClick = { colorIndex = index },
                            )
                        }
                    }

                    // Avatar picker — the name's initial (index 0) plus a row of emoji faces. D-pad
                    // scrolls the row; the chosen face shows on the selected colour.
                    Text("Avatar", style = MaterialTheme.typography.titleMedium, color = Brand.OnSurface)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items((0 until profileAvatarOptionCount).toList()) { idx ->
                            AvatarSwatch(
                                emoji = profileAvatarEmoji(idx),
                                initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A",
                                tint = profileAvatarColor(colorIndex),
                                selected = idx == avatarIndex,
                                onClick = { avatarIndex = idx },
                            )
                        }
                    }

                    // Actions
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onDelete != null) {
                            DialogButton(
                                label = "Delete",
                                onClick = onDelete,
                                container = Brand.SurfaceVariant,
                                ring = Brand.Error,
                            )
                        }
                        Box(modifier = Modifier.weight(1f))
                        DialogButton(
                            label = "Cancel",
                            onClick = onDismiss,
                            container = Brand.SurfaceVariant,
                            ring = Brand.OnSurfaceDim,
                        )
                        DialogButton(
                            label = confirmLabel,
                            onClick = { if (name.isNotBlank()) onSave(name.trim(), isKids, colorIndex, avatarIndex) },
                            container = Brand.Violet,
                            ring = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color,
            focusedContainerColor = color,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.12f),
        modifier = Modifier.size(44.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .border(3.dp, Color.White, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarSwatch(
    emoji: String?,
    initial: String,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = tint, focusedContainerColor = tint),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.14f),
        modifier = Modifier.size(56.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (selected) Modifier.border(3.dp, Color.White, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (emoji != null) {
                Text(emoji, fontSize = 26.sp)
            } else {
                Text(initial, color = Color.White, fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    onClick: () -> Unit,
    container: Color,
    ring: Color,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = container,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, ring), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.05f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
