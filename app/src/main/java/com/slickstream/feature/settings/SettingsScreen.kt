package com.slickstream.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.data.settings.CacheSize
import com.slickstream.data.settings.QualityPreference
import com.slickstream.data.settings.SubtitleLanguage
import com.slickstream.data.settings.SubtitleSize
import com.slickstream.data.settings.SubtitleStyle
import com.slickstream.data.settings.UiDensity
import com.slickstream.feature.profile.ConfirmDialog
import com.slickstream.ui.theme.Brand

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshCacheStats() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Brand.OnSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Brand.OnSurface,
            )
        }

        Spacer(Modifier.height(8.dp))

        SettingsSection("Streaming quality") {
            OptionGroup(
                label = "On Wi-Fi",
                options = QualityPreference.entries,
                selected = settings.wifiQuality,
                labelOf = { it.label },
                onSelect = viewModel::setWifiQuality,
            )
            Spacer(Modifier.height(16.dp))
            OptionGroup(
                label = "On mobile data",
                options = QualityPreference.entries,
                selected = settings.cellularQuality,
                labelOf = { it.label },
                onSelect = viewModel::setCellularQuality,
            )
            Spacer(Modifier.height(16.dp))
            OptionGroup(
                label = "File size",
                options = com.slickstream.data.settings.StreamSizePreference.entries,
                selected = settings.streamSize,
                labelOf = { it.label },
                onSelect = viewModel::setStreamSize,
            )
            Spacer(Modifier.height(6.dp))
            Hint("Caps the quality of the source auto-picked when you hit Play, and how big a file to prefer within that quality (a 1080p episode can be 700 MB or 4 GB). You can still pick any source manually from the player's Quality sheet.")
        }

        SettingsSection("Display") {
            OptionGroup(
                label = "Density",
                options = UiDensity.entries,
                selected = settings.density,
                labelOf = { it.label },
                onSelect = viewModel::setDensity,
            )
            Spacer(Modifier.height(6.dp))
            Hint("Compact fits more on screen; Spacious makes everything larger. Applies instantly across the whole app.")
        }

        SettingsSection("Subtitles") {
            OptionGroup(
                label = "Show subtitles by default",
                options = listOf(true, false),
                selected = settings.subtitlesEnabled,
                labelOf = { if (it) "On" else "Off" },
                onSelect = viewModel::setSubtitlesEnabled,
            )
            Spacer(Modifier.height(16.dp))
            OptionGroup(
                label = "Preferred language",
                options = SubtitleLanguage.entries,
                selected = settings.subtitleLanguage,
                labelOf = { it.label },
                onSelect = viewModel::setSubtitleLanguage,
            )
            Spacer(Modifier.height(16.dp))
            OptionGroup(
                label = "Text size",
                options = SubtitleSize.entries,
                selected = settings.subtitleSize,
                labelOf = { it.label },
                onSelect = viewModel::setSubtitleSize,
            )
            Spacer(Modifier.height(16.dp))
            OptionGroup(
                label = "Style",
                options = SubtitleStyle.entries,
                selected = settings.subtitleStyle,
                labelOf = { it.label },
                onSelect = viewModel::setSubtitleStyle,
            )
            Spacer(Modifier.height(6.dp))
            Hint("When on, the player auto-selects this language if a match is found. You can always change subtitles, size and style from the CC button or here.")
        }

        SettingsSection("Storage") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brand.Surface)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (cacheStats.loading) "Calculating…" else formatBytes(cacheStats.sizeBytes),
                        style = MaterialTheme.typography.titleMedium,
                        color = Brand.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = pluralizeTitles(cacheStats.titleCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.OnSurfaceDim,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Clear cache",
                style = MaterialTheme.typography.labelLarge,
                color = Brand.Error,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brand.SurfaceVariant)
                    .clickable(enabled = !cacheStats.loading && cacheStats.sizeBytes > 0) {
                        showClearCacheDialog = true
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(16.dp))
            OptionGroup(
                label = "Max cache size",
                options = CacheSize.entries,
                selected = settings.maxCacheSize,
                labelOf = { it.label },
                onSelect = viewModel::setMaxCacheSize,
            )
            Spacer(Modifier.height(6.dp))
            Hint("When the cache exceeds this size, the oldest titles are removed automatically. The title you're watching is never removed.")
        }

        Spacer(Modifier.height(32.dp))
    }

    if (showClearCacheDialog) {
        ConfirmDialog(
            title = "Clear cache?",
            message = "This deletes all downloaded torrent data from this device. Anything you're watching right now is unaffected. This can't be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                viewModel.clearCache()
                showClearCacheDialog = false
            },
            onDismiss = { showClearCacheDialog = false },
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun pluralizeTitles(count: Int): String =
    if (count == 1) "1 cached title" else "$count cached titles"

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Brand.Cyan,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun <T> OptionGroup(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.OnSurface,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                Chip(
                    text = labelOf(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.White else Brand.OnSurface,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Brand.Violet else Brand.SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Brand.OnSurfaceDim,
    )
}
