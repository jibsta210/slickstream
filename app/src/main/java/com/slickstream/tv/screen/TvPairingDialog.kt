package com.slickstream.tv.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.slickstream.ui.theme.Brand

/**
 * Full-screen brand overlay that drives the Google device-pairing flow on TV (never a native
 * dialog). The TV shows a code + google.com/device; the user approves on their phone. On success it
 * lands the same Firebase session the phone uses, so favourites/history sync identically.
 */
@Composable
fun TvPairingDialog(
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: TvPairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(state) { if (state is PairingUiState.Success) onSignedIn() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brand.Background.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(680.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brand.Surface)
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (val s = state) {
                is PairingUiState.AwaitingApproval -> {
                    Text("Pair this TV", style = MaterialTheme.typography.headlineMedium, color = Brand.OnSurface)
                    Text(
                        text = "On your phone, open  ${s.verificationUrl}  and enter this code:",
                        style = MaterialTheme.typography.titleMedium,
                        color = Brand.OnSurfaceDim,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = s.userCode,
                        fontSize = 64.sp,
                        color = Brand.Violet,
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Text(
                        text = "Waiting for approval…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.OnSurfaceDim,
                    )
                    Button(onClick = { viewModel.cancel(); onDismiss() }) { Text("Cancel") }
                }
                is PairingUiState.Error -> {
                    Text("Couldn't pair", style = MaterialTheme.typography.headlineSmall, color = Brand.OnSurface)
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Brand.Error,
                        textAlign = TextAlign.Center,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { viewModel.start() }) { Text("Try again") }
                        Button(onClick = { viewModel.cancel(); onDismiss() }) { Text("Close") }
                    }
                }
                else -> {
                    Text("Starting…", style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface)
                }
            }
        }
    }
}
