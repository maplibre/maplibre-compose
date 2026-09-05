package org.maplibre.compose.demoapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.maplibre.compose.demoapp.design.DropdownRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.TileLodOptions

/**
 * Which of the two chosen map styles applies: the system's light or dark choice, or a forced light
 * or dark style.
 */
enum class MapStyleMode(val displayName: String) {
  System("Auto"),
  Light("Light"),
  Dark("Dark");

  val isDark: Boolean
    @Composable
    get() =
      when (this) {
        System -> isSystemInDarkTheme()
        Light -> false
        Dark -> true
      }

  val next: MapStyleMode
    get() =
      when (this) {
        System -> Light
        Light -> Dark
        Dark -> System
      }
}

/**
 * How the Material 3 chrome colors are generated: Android Material You, or a MapLibre brand palette
 * style.
 */
enum class PaletteMode {
  System,
  Tonal,
  Neutral,
  Vibrant,
  Expressive,
}

/** The palette choices this platform shows in settings. */
expect val paletteModeOptions: List<PaletteMode>

/** The first choice in [paletteModeOptions]: System on Android, Tonal elsewhere. */
val defaultPaletteMode: PaletteMode
  get() = paletteModeOptions.first()

/** App-wide diagnostics and toggles, available regardless of which demo is open. */
@Stable
class DemoSettings {
  var mapStyleMode by mutableStateOf(MapStyleMode.System)
  var paletteMode by mutableStateOf(defaultPaletteMode)
  var gestureOptions by mutableStateOf(GestureOptions.Standard)
  var renderOptions by mutableStateOf(RenderOptions.Standard)
  var tileLodOptions by mutableStateOf(TileLodOptions.Standard)
  var showFpsOverlay by mutableStateOf(false)
  var showCameraOverlay by mutableStateOf(false)
  var showPointerPinDiagnostics by mutableStateOf(false)
  var useMaterial3Controls by mutableStateOf(true)
  var showZoomButtons by mutableStateOf(true)
}

@Composable fun rememberDemoSettings() = remember { DemoSettings() }

/**
 * The rendering toggles this platform's [RenderOptions] offers — debug flags, the frame rate cap,
 * and on Android the texture-versus-surface choice — as settings list items.
 */
@Composable expect fun RenderSettingsItems(settings: DemoSettings)

/** Presets for [TileLodOptions], shared because every platform exposes the same three. */
@Composable
fun TileLodSettingsItems(settings: DemoSettings) {
  SectionHeader("Tile level of detail")
  DropdownRow(
    label = "When the camera is pitched",
    options =
      listOf(TileLodOptions.Standard, TileLodOptions.Performance, TileLodOptions.HighDetail),
    selected = settings.tileLodOptions,
    optionLabel = {
      when (it) {
        TileLodOptions.Standard -> "Standard"
        TileLodOptions.Performance -> "Fewer tiles"
        TileLodOptions.HighDetail -> "More detail"
        else -> "Custom"
      }
    },
    onSelect = { settings.tileLodOptions = it },
  )
}
