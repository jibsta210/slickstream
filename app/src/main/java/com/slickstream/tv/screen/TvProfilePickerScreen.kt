package com.slickstream.tv.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.slickstream.core.model.Profile
import com.slickstream.data.local.entity.ProfileEntity
import com.slickstream.feature.profile.ProfileViewModel
import com.slickstream.tv.components.TvConfirmDialog
import com.slickstream.tv.components.TvProfileEditDialog
import com.slickstream.ui.components.ProfileAvatar
import com.slickstream.ui.theme.Brand

/**
 * Android TV "Who's watching?" — a D-pad-focusable row of large profile avatars (focus ring + glow
 * per the TV pattern). Centre/Enter on an avatar switches the active profile and returns to Home.
 * An "Add profile" tile opens the styled TV input dialog; a "Manage" toggle flips into edit mode
 * (centre on an avatar opens edit/delete). The default profile cannot be deleted.
 */
@Composable
fun TvProfilePickerScreen(
    onProfileChosen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()

    var managing by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var initialFocusRequested by remember { mutableStateOf(false) }

    // Land on the active profile rather than whichever profile happens to be first in the database.
    // Once focus succeeds, later repository updates must not yank the D-pad back to this tile.
    val firstFocus = remember { FocusRequester() }
    val initialFocusId = activeProfile?.id ?: profiles.firstOrNull()?.id
    LaunchedEffect(initialFocusId) {
        if (initialFocusId == null || initialFocusRequested) return@LaunchedEffect
        repeat(20) {
            kotlinx.coroutines.delay(40)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) {
                initialFocusRequested = true
                return@LaunchedEffect
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            .padding(start = 56.dp, end = 56.dp, top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (managing) "Manage profiles" else "Switch profile",
                style = MaterialTheme.typography.headlineMedium,
                color = Brand.OnSurface,
            )
            activeProfile?.let { active ->
                Text(
                    text = "Currently watching as ${active.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.OnSurfaceDim,
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                TvProfileTile(
                    profile = profile,
                    managing = managing,
                    active = profile.id == activeProfile?.id,
                    onClick = {
                        if (managing) {
                            editing = profile
                        } else {
                            viewModel.select(profile.id, onSelected = onProfileChosen)
                        }
                    },
                    modifier = if (profile.id == initialFocusId) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    },
                )
            }
            item {
                TvAddTile(onClick = { showCreate = true })
            }
        }

        TvManageButton(
            managing = managing,
            onToggle = { managing = !managing },
        )
    }

    if (showCreate) {
        TvProfileEditDialog(
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
        TvProfileEditDialog(
            title = "Edit profile",
            initialName = profile.name,
            initialIsKids = profile.isKids,
            initialColorIndex = profile.colorIndex,
            initialAvatarIndex = profile.avatarIndex,
            confirmLabel = "Save",
            onSave = { name, isKids, colorIndex, avatarIndex ->
                viewModel.update(profile.copy(name = name, isKids = isKids, colorIndex = colorIndex, avatarIndex = avatarIndex))
                editing = null
            },
            onDelete = if (isDefault) null else {
                {
                    deleting = profile
                    editing = null
                }
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { profile ->
        TvConfirmDialog(
            title = "Delete ${profile.name}?",
            message = "This removes the profile and its favourites + watch history on this device. It can't be undone.",
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

@Composable
private fun TvProfileTile(
    profile: Profile,
    managing: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    Column(
        modifier = Modifier.width(160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(142.dp), contentAlignment = Alignment.Center) {
            if (active) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(4.dp, Brand.Violet, shape),
                )
            }
            Surface(
                onClick = onClick,
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = shape),
                ),
                scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
                modifier = modifier.size(132.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ProfileAvatar(
                        name = profile.name,
                        colorIndex = profile.colorIndex,
                        isKids = profile.isKids,
                        avatarIndex = profile.avatarIndex,
                        size = 132.dp,
                    )
                    if (managing) {
                        Box(
                            modifier = Modifier
                                .size(132.dp)
                                .background(Color(0x88000000), shape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = "Edit ${profile.name}",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }
                }
            }
            if (active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(34.dp)
                        .background(Brand.Background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Active profile",
                        tint = Brand.Violet,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = profile.name,
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (active || profile.isKids) {
            Text(
                text = listOfNotNull(
                    "Active".takeIf { active },
                    "Kids".takeIf { profile.isKids },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = if (active) Brand.Violet else Brand.Star,
            )
        }
    }
}

@Composable
private fun TvAddTile(onClick: () -> Unit) {
    val shape = CircleShape
    Column(
        modifier = Modifier.width(160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Brand.Surface,
                focusedContainerColor = Brand.Violet,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = shape),
            ),
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.08f),
            modifier = Modifier.size(132.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, Brand.OnSurfaceDim, shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "Add profile",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Add profile",
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurfaceDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TvManageButton(
    managing: Boolean,
    onToggle: () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            focusedContainerColor = Brand.Violet,
            contentColor = Brand.OnSurface,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Brand.Violet), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.03f),
        modifier = Modifier.height(56.dp).width(220.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (managing) "Done" else "Manage profiles",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
