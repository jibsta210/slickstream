package com.slickstream.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.ui.theme.Brand

/**
 * Single host for the in-app updater. Drop this once in MainActivity (covers PhoneApp + TvApp).
 * Renders nothing unless an update is available / downloading. Styled card — no native dialog.
 */
@Composable
fun UpdateGate(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is UpdateUiState.Available -> UpdateDialog(
            title = "Update available",
            body = s.manifest.notes.ifBlank { "Version ${s.manifest.versionName} is ready to install." },
            primaryLabel = "Update now",
            onPrimary = { viewModel.startDownload(s.manifest) },
            secondaryLabel = if (s.manifest.mandatory) null else "Later",
            onSecondary = { viewModel.dismiss(s.manifest) },
            dismissable = !s.manifest.mandatory,
        )
        is UpdateUiState.Downloading -> UpdateDialog(
            title = "Downloading update",
            body = "${s.percent}%",
            progress = s.percent / 100f,
            dismissable = false,
        )
        is UpdateUiState.ReadyToInstall -> UpdateDialog(
            title = "Ready to install",
            body = "Tap Install to finish. If prompted, allow installing unknown apps for SlickStream.",
            primaryLabel = "Install",
            onPrimary = { viewModel.launchInstall() },
            secondaryLabel = "Later",
            onSecondary = { viewModel.dismiss(s.manifest) },
            dismissable = true,
        )
        is UpdateUiState.Error -> UpdateDialog(
            title = "Update failed",
            body = s.message,
            primaryLabel = "Dismiss",
            onPrimary = { /* leave; next launch retries */ },
            dismissable = true,
        )
        else -> Unit
    }
}

@Composable
private fun UpdateDialog(
    title: String,
    body: String,
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    progress: Float? = null,
    dismissable: Boolean = true,
) {
    Dialog(
        onDismissRequest = { if (dismissable) onSecondary() },
        properties = DialogProperties(
            dismissOnBackPress = dismissable,
            dismissOnClickOutside = dismissable,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Brand.Surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Brand.OnSurface,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.OnSurfaceDim,
                )
                if (progress != null) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50)),
                        color = Brand.Violet,
                        trackColor = Brand.SurfaceVariant,
                    )
                }
                if (primaryLabel != null || secondaryLabel != null) {
                    Spacer(Modifier.height(24.dp))
                    // Land the D-pad on the primary action so a TV remote can drive the dialog
                    // (a mandatory update with no focusable button would otherwise soft-lock a TV).
                    val primaryFocus = remember { FocusRequester() }
                    LaunchedEffect(primaryLabel) {
                        if (primaryLabel != null) runCatching { primaryFocus.requestFocus() }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (secondaryLabel != null) {
                            PillButton(secondaryLabel, ghost = true, onClick = onSecondary)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (primaryLabel != null) {
                            PillButton(
                                primaryLabel,
                                ghost = false,
                                onClick = onPrimary,
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    ghost: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Material3 buttons are focusable + DPAD_CENTER-operable, so the dialog works on a TV remote
    // (the old clickable Text was not focusable). Still a styled Compose dialog — never native.
    if (ghost) {
        TextButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.textButtonColors(contentColor = Brand.OnSurfaceDim),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Brand.Violet,
                contentColor = Brand.Background,
            ),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
