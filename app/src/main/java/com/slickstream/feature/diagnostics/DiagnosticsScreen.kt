package com.slickstream.feature.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.slickstream.core.diagnostics.Diagnostics
import com.slickstream.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    val diagnostics: Diagnostics,
) : ViewModel()

/**
 * On-screen diagnostics — works on ANY device with no Play Services / Firebase / adb needed. Shows the
 * environment (WebView + Play Services presence, app version) and the last captured crash report, so a
 * user on a box where remote diagnostics can't upload can just read/photograph this screen.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val d = viewModel.diagnostics
    var crash by remember { mutableStateOf(d.lastCrashReport()) }
    val env = remember { d.environmentReport() }

    // Build the full text as lines so a TV D-pad scrolls the LazyColumn naturally.
    val lines = remember(crash) {
        buildList {
            add("=== ENVIRONMENT ===")
            addAll(env.trimEnd().split("\n"))
            add("")
            add("=== LAST CRASH ===")
            if (crash.isNullOrBlank()) add("(no crash recorded on this device)")
            else addAll(crash!!.split("\n"))
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Brand.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Brand.OnSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface)
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = { d.clearLastCrash(); crash = null },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.SurfaceVariant, contentColor = Brand.OnSurface),
            ) { Text("Clear crash") }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    color = when {
                        line.startsWith("===") -> Brand.Cyan
                        line.contains("NOT AVAILABLE") || line.contains("NOT PRESENT") -> Brand.Error
                        else -> Brand.OnSurfaceDim
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
