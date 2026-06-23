package com.slickstream.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.slickstream.BuildConfig
import com.slickstream.core.model.UserProfile
import com.slickstream.feature.auth.AuthViewModel
import com.slickstream.ui.components.SectionHeader
import com.slickstream.ui.theme.Brand

/**
 * Profile tab. Shows the signed-in Google identity (avatar/name/email) with a styled sign-out
 * action, or a "Sign in with Google" call-to-action when signed out. Also surfaces app version
 * and a destructive "Clear watch history" action.
 *
 * Auth/user state is reused from [AuthViewModel] (owned by feature.auth); the local "clear
 * history" action is handled by [ProfileViewModel].
 */
@Composable
fun ProfileScreen(
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    authViewModel: AuthViewModel = hiltViewModel(),
    profileViewModel: ProfileViewModel = hiltViewModel(),
) {
    val user by authViewModel.user.collectAsStateWithLifecycle()

    var showSignOutDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader(title = "Profile")

        if (user != null) {
            SignedInHeader(user = user!!)
        } else {
            SignedOutHeader(onSignIn = onSignIn)
        }

        Spacer(Modifier.height(24.dp))

        SectionHeader(title = "Preferences")
        SettingRow(
            icon = { Icon(Icons.Rounded.Settings, contentDescription = null, tint = Brand.Cyan) },
            title = "Settings",
            subtitle = "Streaming quality per network and display density.",
            onClick = onOpenSettings,
        )

        Spacer(Modifier.height(24.dp))

        SectionHeader(title = "Library")
        SettingRow(
            icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = Brand.Error) },
            title = "Clear watch history",
            subtitle = "Remove every \"continue watching\" entry from this device.",
            onClick = { showClearHistoryDialog = true },
        )

        Spacer(Modifier.height(24.dp))

        SectionHeader(title = "About")
        SettingRow(
            icon = { Icon(Icons.Rounded.Person, contentDescription = null, tint = Brand.OnSurfaceDim) },
            title = "SlickStream",
            subtitle = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            onClick = null,
        )

        if (user != null) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { showSignOutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Error),
            ) {
                Icon(Icons.Rounded.Logout, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Sign out", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    if (showSignOutDialog) {
        ConfirmDialog(
            title = "Sign out?",
            message = "You'll need to sign in again to sync your favourites across devices.",
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                authViewModel.signOut()
                showSignOutDialog = false
            },
            onDismiss = { showSignOutDialog = false },
        )
    }

    if (showClearHistoryDialog) {
        ConfirmDialog(
            title = "Clear watch history?",
            message = "This removes all of your \"continue watching\" progress on this device. This can't be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                profileViewModel.clearWatchHistory()
                showClearHistoryDialog = false
            },
            onDismiss = { showClearHistoryDialog = false },
        )
    }
}

@Composable
private fun SignedInHeader(
    user: UserProfile,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = Brand.Surface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(photoUrl = user.photoUrl)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = user.displayName?.takeIf { it.isNotBlank() } ?: "Signed in",
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.OnSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                user.email?.takeIf { it.isNotBlank() }?.let { email ->
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.OnSurfaceDim,
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar(
    photoUrl: String?,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    if (!photoUrl.isNullOrBlank()) {
        AsyncImage(
            model = photoUrl,
            contentDescription = "Profile photo",
            modifier = modifier
                .size(56.dp)
                .clip(shape)
                .background(Brand.SurfaceVariant, shape),
        )
    } else {
        Box(
            modifier = modifier
                .size(56.dp)
                .clip(shape)
                .background(Brand.Violet, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun SignedOutHeader(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = Brand.Surface,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(photoUrl = null)
            Text(
                text = "You're not signed in",
                style = MaterialTheme.typography.titleMedium,
                color = Brand.OnSurface,
            )
            Text(
                text = "Sign in with Google to sync favourites and watch history.",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.OnSurfaceDim,
            )
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Violet,
                    contentColor = Color.White,
                ),
            ) {
                Text("Sign in with Google", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = Brand.Surface,
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            icon()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.OnSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurfaceDim,
                )
            }
        }
    }
}
