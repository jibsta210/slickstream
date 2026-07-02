package com.slickstream.tv.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.slickstream.core.model.MediaItem
import com.slickstream.feature.search.SearchViewModel
import com.slickstream.tv.components.TvPosterCard
import com.slickstream.tv.components.TvSearchField
import com.slickstream.ui.theme.Brand

/**
 * Android TV Search — VOICE FIRST. A large pulsing mic tile sits front-and-centre (Android TV
 * remotes have a dedicated mic); pressing it starts [com.slickstream.feature.search.VoiceSearchManager]
 * via [SearchViewModel]. An on-screen keyboard tile is offered as a fallback for typing. Results
 * land in a focusable poster grid. All recognition/search logic is reused from [SearchViewModel].
 */
@Composable
fun TvSearchScreen(
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Strip the high-frequency rmsDb BEFORE collecting at screen scope: raw voiceState ticks at
    // mic-callback rate while listening, and each tick recomposed the ENTIRE screen (hero, grid,
    // transcript) — a sustained storm exactly while SpeechRecognizer is eating CPU. MicTile
    // collects the loudness by itself, confining that churn to one small tile.
    val voice by remember {
        viewModel.voiceState.map { it.copy(rmsDb = 0f) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.voiceState.value.copy(rmsDb = 0f))
    val rmsFlow = remember { viewModel.voiceState.map { it.rmsDb } }

    var showKeyboard by remember { mutableStateOf(false) }

    // Land focus on the mic (the voice-first hero) on entry.
    val micFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(showKeyboard) {
        if (!showKeyboard) {
            repeat(12) {
                kotlinx.coroutines.delay(40)
                if (runCatching { micFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    // RECORD_AUDIO permission launcher; on grant we immediately start listening.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startVoice()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // No background here — TvApp's shell already fills Brand.Background; repeating it was
            // a second full-screen fill pass per frame on a fill-rate-bound GPU.
            // Outer pad trimmed — the screen-wide overscan inset in TvApp supplies the safe margin.
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.OnSurface,
        )

        // --- Voice-first hero -------------------------------------------------
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MicTile(
                    listening = voice.isListening,
                    rmsDbFlow = rmsFlow,
                    focusRequester = micFocus,
                    onClick = {
                        // Ask for the mic permission first if needed, else start directly.
                        viewModel.acknowledgeVoice()
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
                KeyboardTile(onClick = { showKeyboard = true })
            }
        }

        // Live transcript / prompt under the mic.
        val transcript = when {
            voice.isListening && state.query.isNotBlank() -> "\"${state.query}\""
            voice.isListening -> "Listening…"
            voice.error != null -> voice.error!!
            state.query.isNotBlank() -> "\"${state.query}\""
            else -> "Press the mic and say a title, actor, or genre"
        }
        Text(
            text = transcript,
            style = MaterialTheme.typography.titleMedium,
            color = if (voice.error != null && !voice.isListening) Brand.Error else Brand.OnSurfaceDim,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        // Optional on-screen text entry.
        if (showKeyboard) {
            TvSearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                onSubmit = viewModel::submit,
            )
        }

        // --- Results ----------------------------------------------------------
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading -> TvLoading()
                state.errorMessage != null -> TvErrorRetry(
                    message = state.errorMessage!!,
                    onRetry = viewModel::submit,
                )
                state.isEmpty -> CenteredHint("No results for \"${state.query}\".")
                state.results.isEmpty() -> CenteredHint("Search results will appear here.")
                else -> ResultsGrid(results = state.results, onMediaClick = onMediaClick)
            }
        }
    }
}

@Composable
private fun MicTile(
    listening: Boolean,
    rmsDbFlow: Flow<Float>,
    onClick: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // Pulse the ring with mic loudness while listening. Loudness is collected HERE so its ticks
    // recompose only this tile, and the animated scale is applied in graphicsLayer's lambda —
    // a draw-phase read, so the pulse animation itself never recomposes anything.
    val rmsDb by rmsDbFlow.collectAsStateWithLifecycle(initialValue = 0f)
    val pulse = animateFloatAsState(
        targetValue = if (listening) 1f + (rmsDb.coerceIn(0f, 12f) / 12f) * 0.18f else 1f,
        label = "mic-pulse",
    )

    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (listening) Brand.Violet else Brand.Surface,
            focusedContainerColor = Brand.Violet,
            pressedContainerColor = Brand.VioletDim,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Brand.Cyan),
                shape = CircleShape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.08f),
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .size(168.dp)
            .graphicsLayer {
                val s = if (listening) pulse.value else 1f
                scaleX = s
                scaleY = s
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = "Voice search",
                tint = if (listening || focused) Brand.OnSurface else Brand.Violet,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun KeyboardTile(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            focusedContainerColor = Brand.Violet,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
        modifier = Modifier.size(width = 168.dp, height = 100.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = "Type to search",
                tint = Brand.OnSurface,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text("Type", style = MaterialTheme.typography.labelLarge, color = Brand.OnSurface)
        }
    }
}

@Composable
private fun ResultsGrid(
    results: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results, key = { "${it.mediaType.name}-${it.id}" }) { item ->
            TvPosterCard(item = item, onClick = onMediaClick, fillCell = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurfaceDim,
            textAlign = TextAlign.Center,
        )
    }
}
