package com.slickstream.feature.live

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.slickstream.ui.theme.Brand

/**
 * Full-screen HLS player for a live-sports feed. Uses Media3's built-in transport controls (which
 * work with both touch and the TV D-pad), with a brand back button + buffering / error overlays.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LivePlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler { onBack() }

    // Land the D-pad on the back button and keep it there, so TV always has a visible exit.
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }

    val player by viewModel.player.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        // No Media3 controller: on Android TV it swallows the BACK key (to hide its
                        // own controls) so BACK never reaches the Compose BackHandler — that's why
                        // the user got stuck. It also traps D-pad focus. We draw our own back button.
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        when (val s = state) {
            LivePlayerViewModel.UiState.Buffering -> CenterOverlay {
                CircularProgressIndicator(color = Brand.Violet, strokeWidth = 4.dp, modifier = Modifier.size(52.dp))
                Text(viewModel.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Connecting to live feed…", style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim)
            }

            is LivePlayerViewModel.UiState.Error -> CenterOverlay {
                Text("Couldn't play this feed", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim, textAlign = TextAlign.Center)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::retry) { Text("Retry") }
                    Button(onClick = onBack) { Text("Back") }
                }
            }

            LivePlayerViewModel.UiState.NoStream -> CenterOverlay {
                Text("No stream selected", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Button(onClick = onBack) { Text("Back") }
            }

            LivePlayerViewModel.UiState.Playing -> Unit
        }

        // Brand back button, top-left — focusable + auto-focused so a TV remote can always exit.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .focusRequester(backFocus)
                .clip(CircleShape)
                .background(Color(0x66000000)),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CenterOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)).align(Alignment.Center),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.width(520.dp),
        ) { content() }
    }
}
