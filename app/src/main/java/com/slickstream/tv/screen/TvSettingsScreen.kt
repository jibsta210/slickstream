package com.slickstream.tv.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
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
    onOpenCalibration: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cache by viewModel.cacheStats.collectAsStateWithLifecycle()

    // Rail is hidden here — land focus on the first setting so the first D-pad press isn't swallowed.
    val firstFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            // Outer pad trimmed — the screen-wide overscan inset in TvApp supplies the safe margin.
            .padding(horizontal = 8.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Brand.OnSurface)
        }

        item {
            TvSettingSection("Streaming quality") {
                TvOptionRow("On Wi-Fi / Ethernet", QualityPreference.entries, settings.wifiQuality, { it.label }, viewModel::setWifiQuality, firstChipFocus = firstFocus)
                TvOptionRow("On mobile data", QualityPreference.entries, settings.cellularQuality, { it.label }, viewModel::setCellularQuality)
                TvOptionRow("File size (within quality)", com.slickstream.data.settings.StreamSizePreference.entries, settings.streamSize, { it.label }, viewModel::setStreamSize)
            }
        }

        item {
            TvSettingSection("Display") {
                TvOptionRow("Interface density", UiDensity.entries, settings.density, { it.label }, viewModel::setDensity)
                val calShape = RoundedCornerShape(50)
                Surface(
                    onClick = onOpenCalibration,
                    shape = ClickableSurfaceDefaults.shape(shape = calShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Brand.Surface,
                        focusedContainerColor = Brand.Violet,
                        contentColor = Brand.OnSurface,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet), shape = calShape),
                    ),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.03f),
                ) {
                    Text(
                        "Screen calibration (fit to your TV)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
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
                val shape = RoundedCornerShape(50)
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
    firstChipFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(options) { index, option ->
                val isSel = option == selected
                val shape = RoundedCornerShape(50)
                Surface(
                    onClick = { onSelect(option) },
                    shape = ClickableSurfaceDefaults.shape(shape = shape),
                    colors = ClickableSurfaceDefaults.colors(
                        // selected != focused: the active value is a subtle fill + violet text + a dot;
                        // the FOCUSED chip is a solid violet pill + white ring.
                        containerColor = if (isSel) Brand.SurfaceVariant else Brand.Surface,
                        focusedContainerColor = Brand.Violet,
                        contentColor = if (isSel) Brand.Violet else Brand.OnSurface,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = shape),
                    ),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
                    modifier = if (index == 0 && firstChipFocus != null) {
                        Modifier.focusRequester(firstChipFocus)
                    } else {
                        Modifier
                    },
                ) {
                    // Standard TV pill: fixed 48.dp height + 22.dp horizontal pad, titleSmall.
                    Box(
                        modifier = Modifier.height(48.dp).padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isSel) "● ${labelOf(option)}" else labelOf(option),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
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
