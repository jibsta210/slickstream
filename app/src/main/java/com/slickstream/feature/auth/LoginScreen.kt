package com.slickstream.feature.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.ui.theme.Brand

/**
 * Polished phone login. Brand hero up top, a primary "Continue with Google" button, and a
 * ghost "Browse as guest" text button. Sign-in errors surface inline (no native dialogs).
 *
 * @param onSignedIn invoked once a user session is present (after a successful sign-in).
 * @param onSkip invoked when the user chooses to browse as a guest.
 */
@Composable
fun LoginScreen(
    onSignedIn: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // Navigate away as soon as a session is present (covers both fresh sign-in and restore).
    LaunchedEffect(uiState.isSignedIn) {
        if (uiState.isSignedIn) onSignedIn()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brandBackdrop()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            BrandHero()

            Spacer(Modifier.weight(1f))

            // Inline, styled error (no AlertDialog / native toast).
            AnimatedVisibility(
                visible = uiState.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ErrorBanner(message = uiState.errorMessage.orEmpty())
            }

            Spacer(Modifier.height(16.dp))

            GoogleButton(
                isLoading = uiState.isSigningIn,
                enabled = activity != null && !uiState.isSigningIn,
                onClick = { activity?.let(viewModel::signIn) },
            )

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onSkip,
                enabled = !uiState.isSigningIn,
            ) {
                Text(
                    text = "Skip · Browse as guest",
                    style = MaterialTheme.typography.labelLarge,
                    color = Brand.OnSurfaceDim,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BrandHero() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Glyph badge.
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(listOf(Brand.Violet, Brand.VioletDim)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(52.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "SlickStream",
            style = MaterialTheme.typography.displaySmall,
            color = Brand.OnSurface,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Every movie and show. One slick player.",
            style = MaterialTheme.typography.bodyLarge,
            color = Brand.OnSurfaceDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GoogleButton(
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF1F1F1F),
            disabledContainerColor = Color.White.copy(alpha = 0.45f),
            disabledContentColor = Color(0xFF1F1F1F).copy(alpha = 0.6f),
        ),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Brand.Violet,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Signing in…",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            GoogleGlyph()
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Continue with Google",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

/** A small self-contained "G" mark so we don't depend on a vector asset from another module. */
@Composable
private fun GoogleGlyph() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xFF4285F4)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "G",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Error.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.Error,
            textAlign = TextAlign.Start,
        )
    }
}

/** Cinematic vertical wash from deep violet into the near-black background. */
private fun brandBackdrop(): Brush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF1A1330),
        Brand.Background,
        Brand.Background,
    ),
)

/** Unwrap the hosting [Activity] from a (possibly wrapped) Compose [Context]. */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
