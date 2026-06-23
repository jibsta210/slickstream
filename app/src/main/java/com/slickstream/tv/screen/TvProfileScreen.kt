package com.slickstream.tv.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.UserProfile
import com.slickstream.core.repository.AuthRepository
import com.slickstream.feature.profile.ProfileViewModel
import com.slickstream.tv.components.TvConfirmDialog
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

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Android TV Profile. Shows the signed-in Google account (or a sign-in prompt) and library
 * actions. Sign-in launches the Credential Manager flow which needs an [Activity]; sign-out and
 * clear-history are confirmed with a styled brand dialog (never a native AlertDialog).
 */
@Composable
fun TvProfileScreen(
    modifier: Modifier = Modifier,
    profileViewModel: ProfileViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val authRepository = remember { authRepository(context) }
    val user by authRepository.currentUser.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Re-hydrate any persisted session when the screen first appears.
    LaunchedEffect(Unit) { authRepository.restoreSession() }

    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    var signInError by remember { mutableStateOf<String?>(null) }
    var showPairing by remember { mutableStateOf(false) }

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

        ProfileHeader(user = user)

        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(520.dp)) {
            if (user == null) {
                ProfileAction(
                    icon = Icons.Rounded.Login,
                    label = "Sign in with Google",
                    onClick = {
                        // CredentialManager's Google UI isn't available on TV — use device pairing.
                        signInError = null
                        showPairing = true
                    },
                )
            } else {
                ProfileAction(
                    icon = Icons.Rounded.Logout,
                    label = "Sign out",
                    onClick = { dialog = ProfileDialog.SignOut },
                )
            }

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
        TvPairingDialog(
            onDismiss = { showPairing = false },
            onSignedIn = { showPairing = false },
        )
    }
}

private enum class ProfileDialog { SignOut, ClearHistory }

@Composable
private fun ProfileHeader(user: UserProfile?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brand.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (user?.photoUrl != null) {
                AsyncImage(
                    model = user.photoUrl,
                    contentDescription = user.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = Brand.OnSurfaceDim,
                    modifier = Modifier.size(52.dp),
                )
            }
        }
        Spacer(Modifier.width(24.dp))
        Column {
            Text(
                text = user?.displayName ?: user?.email ?: "Guest",
                style = MaterialTheme.typography.titleLarge,
                color = Brand.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (user == null) "Not signed in" else (user.email ?: "Signed in with Google"),
                style = MaterialTheme.typography.bodyLarge,
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
        modifier = Modifier.fillMaxWidth().height(64.dp),
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
