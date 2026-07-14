package com.slickstream.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.core.model.Profile
import com.slickstream.data.local.entity.ProfileEntity
import com.slickstream.ui.components.ProfileAvatar
import com.slickstream.ui.theme.Brand

/**
 * "Who's watching?" — the phone profile picker. A wrap of large profile avatars (name under each);
 * tapping one switches the active profile and returns to Home. An "Add profile" tile opens the
 * styled create dialog. A "Manage" toggle flips into edit mode: tapping an avatar then opens an
 * edit dialog (rename / kids / colour / delete). The default profile can't be deleted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePickerScreen(
    onProfileChosen: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    var managing by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(24.dp))
        Text(
            text = if (managing) "Manage profiles" else "Who's watching?",
            style = MaterialTheme.typography.displaySmall,
            color = Brand.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(40.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            profiles.forEach { profile ->
                ProfileTile(
                    profile = profile,
                    managing = managing,
                    onClick = {
                        if (managing) {
                            editing = profile
                        } else {
                            viewModel.select(profile.id, onSelected = onProfileChosen)
                        }
                    },
                )
            }
            AddProfileTile(onClick = { showCreate = true })
        }

        Spacer(Modifier.size(48.dp))

        TextButton(
            onClick = { managing = !managing },
            colors = ButtonDefaults.textButtonColors(contentColor = Brand.OnSurfaceDim),
        ) {
            Icon(
                imageVector = if (managing) Icons.Rounded.Done else Icons.Rounded.Edit,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = if (managing) "Done" else "Manage profiles",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    if (showCreate) {
        ProfileEditDialog(
            title = "New profile",
            initialName = "",
            initialIsKids = false,
            initialColorIndex = viewModel.nextColorIndex(),
            confirmLabel = "Create",
            onSave = { name, isKids, colorIndex, avatarIndex ->
                viewModel.create(
                    name = name,
                    isKids = isKids,
                    colorIndex = colorIndex,
                    avatarIndex = avatarIndex,
                    onCreated = {
                        showCreate = false
                        onProfileChosen()
                    },
                )
            },
            onDismiss = { showCreate = false },
        )
    }

    editing?.let { profile ->
        val isDefault = profile.id == ProfileEntity.DEFAULT_PROFILE_ID
        ProfileManageDialog(
            profile = profile,
            canDelete = !isDefault,
            onSave = { name, isKids, colorIndex, avatarIndex ->
                viewModel.update(profile.copy(name = name, isKids = isKids, colorIndex = colorIndex, avatarIndex = avatarIndex))
                editing = null
            },
            onDelete = {
                deleting = profile
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { profile ->
        ConfirmDialog(
            title = "Delete ${profile.name}?",
            message = "This removes the profile and its favourites + watch history on this device. This can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                viewModel.delete(profile.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * Edit dialog wrapper that reuses [ProfileEditDialog] for the fields and adds a "Delete profile"
 * action (hidden for the default profile).
 */
@Composable
private fun ProfileManageDialog(
    profile: Profile,
    canDelete: Boolean,
    onSave: (name: String, isKids: Boolean, colorIndex: Int, avatarIndex: Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ProfileEditDialog(
        title = "Edit profile",
        initialName = profile.name,
        initialIsKids = profile.isKids,
        initialColorIndex = profile.colorIndex,
        initialAvatarIndex = profile.avatarIndex,
        confirmLabel = "Save",
        onSave = onSave,
        onDismiss = onDismiss,
        extraAction = if (canDelete) {
            {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = Brand.Error),
                ) {
                    Text("Delete profile", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else null,
    )
}

@Composable
private fun ProfileTile(
    profile: Profile,
    managing: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(108.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ProfileAvatar(
                name = profile.name,
                colorIndex = profile.colorIndex,
                isKids = profile.isKids,
                avatarIndex = profile.avatarIndex,
                size = 84.dp,
            )
            if (managing) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Color(0x88000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Edit ${profile.name}",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = profile.name,
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (profile.isKids) {
            Text(
                text = "Kids",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.Star,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(108.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .border(2.dp, Brand.OnSurfaceDim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Add profile",
                tint = Brand.OnSurfaceDim,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = "Add profile",
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.wrapContentWidth(),
        )
    }
}
