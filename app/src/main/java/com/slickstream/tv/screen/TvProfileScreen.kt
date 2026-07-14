package com.slickstream.tv.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import com.slickstream.core.model.UserProfile
import com.slickstream.core.repository.AuthRepository
import com.slickstream.feature.profile.ProfileViewModel
import com.slickstream.tv.components.TvConfirmDialog
import com.slickstream.ui.components.ProfileAvatar
import com.slickstream.ui.theme.Brand
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * Exposes the singleton [AuthRepository] to composables. Auth/user state is owned by
 * [com.slickstream.feature.auth.AuthRepository] (Google OAuth via Credential Manager); the TV
 * profile screen reads [AuthRepository.currentUser] and drives sign-in/out through it, while the
 * existing [ProfileViewModel] handles clearing local watch history.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TvAuthEntryPoint {
    fun authRepository(): AuthRepository
}

private fun authRepository(context: Context): AuthRepository =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        TvAuthEntryPoint::class.java,
    ).authRepository()

/**
 * Android TV Profile. Shows the active viewing profile, Google sync status and library actions.
 * Sign-out and clear-history are confirmed with a styled brand dialog (never a native
 * AlertDialog).
 */
@Composable
fun TvProfileScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onSwitchProfile: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    profileViewModel: ProfileViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val authRepository = remember { authRepository(context) }
    val user by authRepository.currentUser.collectAsStateWithLifecycle()
    val activeProfile by profileViewModel.activeProfile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Re-hydrate any persisted session when the screen first appears.
    LaunchedEffect(Unit) { authRepository.restoreSession() }

    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    var signInError by remember { mutableStateOf<String?>(null) }
    var showPairing by remember { mutableStateOf(false) }

    // Switching is the primary profile action, so land focus there once when this screen opens.
    val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            .padding(start = 56.dp, end = 56.dp, top = 36.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text(
            text = "Profile",
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.OnSurface,
        )

        ProfileHeader(profile = activeProfile, user = user)

        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(520.dp)) {
            ProfileAction(
                icon = Icons.Rounded.SwitchAccount,
                label = "Switch profile",
                onClick = onSwitchProfile,
                focusRequester = firstFocus,
            )

            if (user == null) {
                ProfileAction(
                    icon = Icons.Rounded.Login,
                    label = "Sign in with Google",
                    onClick = { signInError = null; showPairing = true },
                )
            } else {
                ProfileAction(
                    icon = Icons.Rounded.Logout,
                    label = "Sign out",
                    onClick = { dialog = ProfileDialog.SignOut },
                )
            }

            ProfileAction(
                icon = Icons.Rounded.Download,
                label = "Downloads",
                onClick = onOpenDownloads,
            )

            ProfileAction(
                icon = Icons.Rounded.Settings,
                label = "Settings",
                onClick = onOpenSettings,
            )

            ProfileAction(
                icon = Icons.Rounded.DeleteSweep,
                label = "Clear watch history",
                destructive = true,
                onClick = { dialog = ProfileDialog.ClearHistory },
            )

            signInError?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brand.Error.copy(alpha = 0.14f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Brand.Error,
                    )
                }
            }
        }
    }

    when (dialog) {
        ProfileDialog.SignOut -> TvConfirmDialog(
            title = "Sign out?",
            message = "You'll need to sign in again to sync your favourites across devices.",
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                scope.launch { authRepository.signOut() }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        ProfileDialog.ClearHistory -> TvConfirmDialog(
            title = "Clear watch history?",
            message = "This removes every \"Continue Watching\" entry on this device. It can't be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                profileViewModel.clearWatchHistory()
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        null -> Unit
    }

    if (showPairing) {
        TvCodePairDialog(
            onDismiss = { showPairing = false },
            onSignedIn = { showPairing = false },
        )
    }
}

private enum class ProfileDialog { SignOut, ClearHistory }

@Composable
private fun ProfileHeader(profile: Profile?, user: UserProfile?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(
            name = profile?.name ?: "Guest",
            colorIndex = profile?.colorIndex ?: 0,
            isKids = profile?.isKids == true,
            avatarIndex = profile?.avatarIndex ?: 0,
            size = 96.dp,
        )
        Spacer(Modifier.width(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = profile?.name ?: "Guest",
                style = MaterialTheme.typography.titleLarge,
                color = Brand.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (profile?.isKids == true) {
                    "Active viewing profile · Kids"
                } else {
                    "Active viewing profile"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Brand.Violet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = user?.email?.let { "Syncing with $it" } ?: "Local only · Not signed in",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.OnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProfileAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            focusedContainerColor = if (destructive) Brand.Error else Brand.Violet,
            contentColor = if (destructive) Brand.Error else Brand.OnSurface,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    3.dp,
                    if (destructive) Brand.Error else Brand.Violet,
                ),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.03f),
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(18.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
