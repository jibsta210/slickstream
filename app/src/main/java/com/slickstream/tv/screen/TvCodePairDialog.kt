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
 * Full-screen brand overlay (never a native dialog) that shows the pairing code for the user to
 * enter on their phone (Profile → Link a TV). Signs the TV in when the phone claims the code.
 */
@Composable
fun TvCodePairDialog(
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: TvCodePairViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(state) { if (state is TvCodePairViewModel.State.Success) onSignedIn() }

    Box(
        modifier = Modifier.fillMaxSize().background(Brand.Background.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(720.dp).clip(RoundedCornerShape(24.dp)).background(Brand.Surface).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (val s = state) {
                is TvCodePairViewModel.State.Showing -> {
                    Text("Link this TV", style = MaterialTheme.typography.headlineMedium, color = Brand.OnSurface)
                    Text(
                        text = "On your phone, open SlickStream → Profile → \"Link a TV\" and enter this code:",
                        style = MaterialTheme.typography.titleMedium,
                        color = Brand.OnSurfaceDim,
                        textAlign = TextAlign.Center,
                    )
                    Text(s.code, fontSize = 72.sp, color = Brand.Violet, style = MaterialTheme.typography.displayLarge)
                    Text("Waiting for your phone…", style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim)
                    Button(onClick = { viewModel.cancel(); onDismiss() }) { Text("Cancel") }
                }
                is TvCodePairViewModel.State.Error -> {
                    Text("Couldn't pair", style = MaterialTheme.typography.headlineSmall, color = Brand.OnSurface)
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, color = Brand.Error, textAlign = TextAlign.Center)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { viewModel.start() }) { Text("Try again") }
                        Button(onClick = { viewModel.cancel(); onDismiss() }) { Text("Close") }
                    }
                }
                else -> Text("Starting…", style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface)
            }
        }
    }
}
