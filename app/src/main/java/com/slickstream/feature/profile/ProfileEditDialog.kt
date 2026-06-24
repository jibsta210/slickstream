package com.slickstream.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.slickstream.ui.components.ProfileAvatarColors
import com.slickstream.ui.components.profileAvatarColor
import com.slickstream.ui.theme.Brand

/**
 * Styled, brand-themed create/edit dialog for a profile — name field, a "Kids profile" toggle, and
 * a colour picker. Matches [ConfirmDialog]'s visual language (never a native dialog). Backdrop +
 * back press dismiss as cancel; Save is disabled until the name is non-blank.
 *
 * @param title heading ("New profile" / "Edit profile").
 * @param initialName / [initialIsKids] / [initialColorIndex] prefill for the edit case.
 * @param onSave invoked with the trimmed name, kids flag, and chosen colour index.
 */
@Composable
fun ProfileEditDialog(
    title: String,
    initialName: String,
    initialIsKids: Boolean,
    initialColorIndex: Int,
    onSave: (name: String, isKids: Boolean, colorIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Save",
    extraAction: (@Composable () -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var isKids by remember { mutableStateOf(initialIsKids) }
    var colorIndex by remember { mutableIntStateOf(initialColorIndex) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Brand.Surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .background(Brand.Surface)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.OnSurface,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Brand.SurfaceVariant,
                        unfocusedContainerColor = Brand.SurfaceVariant,
                        focusedTextColor = Brand.OnSurface,
                        unfocusedTextColor = Brand.OnSurface,
                        cursorColor = Brand.Violet,
                        focusedLabelColor = Brand.Violet,
                        unfocusedLabelColor = Brand.OnSurfaceDim,
                        focusedIndicatorColor = Brand.Violet,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Kids profile", style = MaterialTheme.typography.titleMedium, color = Brand.OnSurface)
                        Text(
                            "Shows a family-friendly home with kid-safe rows.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Brand.OnSurfaceDim,
                        )
                    }
                    Switch(
                        checked = isKids,
                        onCheckedChange = { isKids = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Brand.Violet,
                            uncheckedThumbColor = Brand.OnSurfaceDim,
                            uncheckedTrackColor = Brand.SurfaceVariant,
                        ),
                    )
                }

                Text("Colour", style = MaterialTheme.typography.titleMedium, color = Brand.OnSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileAvatarColors.forEachIndexed { index, _ ->
                        val selected = index == ((colorIndex % ProfileAvatarColors.size) + ProfileAvatarColors.size) % ProfileAvatarColors.size
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(profileAvatarColor(index))
                                .then(
                                    if (selected) {
                                        Modifier.border(3.dp, Color.White, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { colorIndex = index },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }

                extraAction?.let {
                    Row(modifier = Modifier.fillMaxWidth()) { it() }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = Brand.OnSurfaceDim),
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = { onSave(name.trim(), isKids, colorIndex) },
                        enabled = name.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Brand.Violet,
                            contentColor = Color.White,
                            disabledContainerColor = Brand.SurfaceVariant,
                            disabledContentColor = Brand.OnSurfaceDim,
                        ),
                    ) {
                        Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
