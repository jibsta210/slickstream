package com.slickstream.tv.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.slickstream.data.settings.CacheSize
import com.slickstream.data.settings.QualityPreference
import com.slickstream.data.settings.SubtitleLanguage
import com.slickstream.data.settings.SubtitleSize
import com.slickstream.data.settings.SubtitleStyle
import com.slickstream.data.settings.UiDensity
import com.slickstream.feature.settings.SettingsViewModel
import com.slickstream.ui.theme.Brand

/**
 * Android TV settings — the phone Settings screen had no TV entry point, so all of these were
 * unreachable on TV. Reuses [SettingsViewModel]; each row is a focusable chip group driven by the
 * D-pad. Reached from the TV Profile screen.
 */
@Composable
fun TvSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cache by viewModel.cacheStats.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            .padding(start = 56.dp, end = 56.dp),
        contentPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Brand.OnSurface)
        }

        item {
            TvSettingSection("Streaming quality") {
                TvOptionRow("On Wi-Fi / Ethernet", QualityPreference.entries, settings.wifiQuality, { it.label }, viewModel::setWifiQuality)
                TvOptionRow("On mobile data", QualityPreference.entries, settings.cellularQuality, { it.label }, viewModel::setCellularQuality)
            }
        }

        item {
            TvSettingSection("Display") {
                TvOptionRow("Interface density", UiDensity.entries, settings.density, { it.label }, viewModel::setDensity)
            }
        }

        item {
            TvSettingSection("Subtitles") {
                TvOptionRow("Show by default", listOf(true, false), settings.subtitlesEnabled, { if (it) "On" else "Off" }, viewModel::setSubtitlesEnabled)
                TvOptionRow("Preferred language", SubtitleLanguage.entries, settings.subtitleLanguage, { it.label }, viewModel::setSubtitleLanguage)
                TvOptionRow("Text size", SubtitleSize.entries, settings.subtitleSize, { it.label }, viewModel::setSubtitleSize)
                TvOptionRow("Style", SubtitleStyle.entries, settings.subtitleStyle, { it.label }, viewModel::setSubtitleStyle)
            }
        }

        item {
            TvSettingSection("Storage") {
                TvOptionRow("Max cache size", CacheSize.entries, settings.maxCacheSize, { it.label }, viewModel::setMaxCacheSize)
                Text(
                    text = if (cache.loading) "Calculating cache…"
                    else "Cached: ${formatBytes(cache.sizeBytes)} · ${cache.titleCount} titles",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurfaceDim,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                val shape = RoundedCornerShape(12.dp)
                Surface(
                    onClick = viewModel::clearCache,
                    shape = ClickableSurfaceDefaults.shape(shape = shape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Brand.Surface,
                        focusedContainerColor = Brand.Error,
                        contentColor = Brand.Error,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Brand.Error), shape = shape),
                    ),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.03f),
                ) {
                    Text("Clear cache", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun TvSettingSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun <T> TvOptionRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(options) { option ->
                val isSel = option == selected
                val shape = RoundedCornerShape(50)
                Surface(
                    onClick = { onSelect(option) },
                    shape = ClickableSurfaceDefaults.shape(shape = shape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSel) Brand.Violet else Brand.SurfaceVariant,
                        focusedContainerColor = Brand.Violet,
                        contentColor = if (isSel) Color.White else Brand.OnSurface,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = shape),
                    ),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
                ) {
                    Text(labelOf(option), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "%.1f GB".format(gb)
    val mb = bytes / 1_000_000.0
    return "%.0f MB".format(mb)
}
