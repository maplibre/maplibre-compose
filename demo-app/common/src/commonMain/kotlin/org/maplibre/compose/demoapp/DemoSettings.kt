package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.TileLodOptions

/** App-wide diagnostics and toggles, available regardless of which demo is open. */
@Stable
class DemoSettings {
  var gestureOptions by mutableStateOf(GestureOptions.Standard)
  var renderOptions by mutableStateOf(RenderOptions.Standard)
  var tileLodOptions by mutableStateOf(TileLodOptions.Standard)
  var showFpsOverlay by mutableStateOf(false)
  var showCameraOverlay by mutableStateOf(false)
  var useMaterial3Controls by mutableStateOf(true)
}

@Composable fun rememberDemoSettings() = remember { DemoSettings() }

/**
 * The rendering toggles this platform's [RenderOptions] offers — debug flags, the frame rate cap,
 * and where supported the texture-versus-surface choice — as settings list items.
 */
@Composable expect fun RenderSettingsItems(settings: DemoSettings)

/** Presets for [TileLodOptions], shared because every platform exposes the same three. */
@Composable
fun TileLodSettingsItems(settings: DemoSettings) {
  SectionHeader("Tile level of detail")
  SegmentedRow(
    label = "When the camera is pitched",
    options =
      listOf(TileLodOptions.Standard, TileLodOptions.Performance, TileLodOptions.HighDetail),
    selected = settings.tileLodOptions,
    optionLabel = {
      when (it) {
        TileLodOptions.Standard -> "Standard"
        TileLodOptions.Performance -> "Fewer"
        TileLodOptions.HighDetail -> "More"
        else -> "Custom"
      }
    },
    onSelect = { settings.tileLodOptions = it },
  )
}
