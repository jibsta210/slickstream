package com.slickstream.tv.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.slickstream.ui.theme.Brand
import androidx.compose.material3.CircularProgressIndicator

/** Centered short message for TV empty / informational states. */
@Composable
fun TvCenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurfaceDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** Centered indeterminate loader for TV screens. */
@Composable
fun TvLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = Brand.Violet,
            trackColor = Brand.SurfaceVariant,
            strokeWidth = 4.dp,
            modifier = Modifier.size(56.dp),
        )
    }
}

/**
 * Friendly TV error state with a focusable "Try again" button (auto-receives initial D-pad
 * focus so the user can retry without hunting for it).
 */
@Composable
fun TvErrorRetry(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(520.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = Brand.OnSurfaceDim,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = Brand.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Try again",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
