package com.slickstream.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.slickstream.ui.theme.Brand
import kotlinx.coroutines.delay

/** A remote control, rather than an opaque message layered over a phone video controller. */
@OptIn(UnstableApi::class)
@Composable
internal fun CastPlaybackControls(
    player: Player?,
    connecting: Boolean,
    converting: Boolean,
    volume: Float,
    muted: Boolean,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
    onRepairAudio: () -> Unit,
    onPlayOnPhone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var playing by remember(player) { mutableStateOf(player?.playWhenReady == true) }
    var seek by remember(player) { mutableStateOf<Float?>(null) }
    var volumeDrag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            duration = player?.duration?.coerceAtLeast(0L) ?: 0L
            playing = player?.playWhenReady == true
            delay(250)
        }
    }
    BoxWithConstraints(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 260.dp
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (connecting) CircularProgressIndicator(Modifier.size(20.dp), color = Brand.Cyan, strokeWidth = 2.dp)
                else Icon(Icons.Default.CastConnected, null, tint = Brand.Cyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        connecting && converting -> "Preparing for your TV…"
                        connecting -> "Connecting to TV…"
                        else -> "Casting to your TV"
                    },
                    color = Brand.OnSurface, modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onPlayOnPhone) { Text("Play on phone") }
            }
            val canSeek = !connecting && duration > 0 &&
                player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (compact) Text(castTime(seek?.let { (it * duration).toLong() } ?: position), color = Brand.OnSurfaceDim)
                Slider(
                    value = seek ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { seek = it },
                    onValueChangeFinished = {
                        seek?.let {
                            position = (it * duration).toLong()
                            player?.seekTo(position)
                        }
                        seek = null
                    },
                    enabled = canSeek,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Cast playback position" },
                )
                if (compact) Text(if (duration > 0) castTime(duration) else "—", color = Brand.OnSurfaceDim)
            }
            if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(castTime(seek?.let { (it * duration).toLong() } ?: position), color = Brand.OnSurfaceDim)
                Text(if (duration > 0) castTime(duration) else "—", color = Brand.OnSurfaceDim)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = { player?.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)) }, enabled = canSeek) {
                    Icon(Icons.Default.Replay10, "Rewind casting 10 seconds", tint = Brand.OnSurface)
                }
                IconButton(
                    onClick = {
                        if (player?.playWhenReady == true) player.pause() else player?.play()
                        playing = player?.playWhenReady == true
                    },
                    enabled = !connecting && player?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true,
                    modifier = Modifier.size(56.dp).background(Brand.Violet, CircleShape),
                ) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause casting" else "Play casting", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = { player?.seekTo((player.currentPosition + 30_000).coerceAtMost(duration)) }, enabled = canSeek) {
                    Icon(Icons.Default.Forward30, "Forward casting 30 seconds", tint = Brand.OnSurface)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMute) {
                    Icon(if (muted || volume == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        if (muted) "Unmute TV" else "Mute TV", tint = Brand.OnSurface)
                }
                Slider(value = volumeDrag ?: volume, onValueChange = { volumeDrag = it },
                    onValueChangeFinished = { volumeDrag?.let(onVolume); volumeDrag = null },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "TV volume" })
                Text("${((volumeDrag ?: volume) * 100).toInt()}%", color = Brand.OnSurfaceDim)
            }
            if (!converting) TextButton(onClick = onRepairAudio, enabled = !connecting) { Text("Fix sound") }
        }
    }
}

private fun castTime(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
