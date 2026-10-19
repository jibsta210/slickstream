package com.slickstream.cast

import android.app.Dialog
import android.view.ContextThemeWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import com.slickstream.ui.theme.Brand

/** Same connection/chooser in the browsing shell and both phone playback backends. */
@Composable
fun CastButton(expanded: Boolean = false, viewModel: CastViewModel = hiltViewModel()) {
    val state by viewModel.connection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    DisposableEffect(Unit) { onDispose { dialog?.dismiss() } }
    val onClick: () -> Unit = {
        val manager = viewModel.manager
        val selector = manager.castContext?.mergedSelector
        if (selector == null) {
            unavailable = true
        } else {
            dialog?.dismiss()
            val themed = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_DayNight)
            dialog = if (manager.isConnected()) MediaRouteControllerDialog(themed)
            else MediaRouteChooserDialog(themed).apply { routeSelector = selector }
            dialog?.show()
        }
    }
    CastDestinationControl(state, expanded, onClick)
    if (unavailable) AlertDialog(
        onDismissRequest = { unavailable = false },
        title = { Text("Cast unavailable") },
        text = { Text("Casting needs Google Play services on this phone. Check that it is installed and up to date.") },
        confirmButton = { TextButton(onClick = { unavailable = false }) { Text("OK") } },
    )
}

@Composable
internal fun CastDestinationControl(state: CastConnectionState, expanded: Boolean, onClick: () -> Unit) {
    val icon = if (state.connected) Icons.Default.CastConnected else Icons.Default.Cast
    val tint = if (state.connected || state.connecting) Brand.Cyan else Brand.OnSurface
    if (expanded) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = "Cast to TV", tint = tint)
            Text(state.label, color = tint, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    } else {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = "Cast to TV", tint = tint) }
    }
}
