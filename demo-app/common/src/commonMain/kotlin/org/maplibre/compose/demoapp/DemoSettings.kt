package org.maplibre.compose.demoapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.demoapp.design.DropdownRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ModifierMatch.Containing
import org.maplibre.compose.interaction.ScrollResponse
import org.maplibre.compose.map.MapUiOptions
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

/** Material You on Android or a palette based on the MapLibre brand color. */
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

/** How the camera travels to a demo, a pointer pin, or a followed location. */
enum class FlightStyle(val title: String) {
  Fly("Fly"),
  Ease("Ease"),
}

/** App-wide diagnostics and toggles, available regardless of which demo is open. */
@Stable
class DemoSettings {
  var mapStyleMode by mutableStateOf(MapStyleMode.System)
  var paletteMode by mutableStateOf(defaultPaletteMode)
  var renderOptions by mutableStateOf(RenderOptions.Standard)
  var uiOptions by mutableStateOf(MapUiOptions.Standard)
  var showFpsOverlay by mutableStateOf(false)
  var showCameraOverlay by mutableStateOf(false)
  var showPointerPinDiagnostics by mutableStateOf(false)
  var useMaterial3Controls by mutableStateOf(true)
  var showZoomButtons by mutableStateOf(true)

  var panEnabled by mutableStateOf(true)
  var rotateEnabled by mutableStateOf(true)
  var tiltEnabled by mutableStateOf(true)

  /** A wheel or trackpad pans instead of zooming; Ctrl while scrolling zooms. */
  var scrollPans by mutableStateOf(false)

  var flightStyle by mutableStateOf(FlightStyle.Fly)
  var paceFlightBySpeed by mutableStateOf(false)
  var flightDurationMillis by mutableStateOf(2000f)
  var flightSpeed by mutableStateOf(CameraAnimation.Fly.DefaultSpeed.toFloat())

  /** Zero means no limit. */
  var flightMinZoom by mutableStateOf(0f)

  /** The camera movements the gesture settings allow. A demo edits these for its own needs. */
  val interactions: MapInteractions
    get() = MapInteractions {
      camera {
        pan { enabled = panEnabled }
        rotate { enabled = rotateEnabled }
        tilt { enabled = tiltEnabled }
      }
    }

  /** [uiOptions] with the scroll binding the settings ask for. */
  val boundUiOptions: MapUiOptions
    get() =
      if (!scrollPans) uiOptions
      else
        MapUiOptions(uiOptions) {
          bindings {
            scroll {
              mappings {
                on(modifiers = Containing(KeyModifier.Ctrl), response = ScrollResponse.Zoom)
                otherwise(ScrollResponse.Pan)
              }
            }
          }
        }

  val flightAnimation: CameraAnimation
    get() =
      when (flightStyle) {
        FlightStyle.Ease -> CameraAnimation.Ease(flightDurationMillis.roundToInt().milliseconds)
        FlightStyle.Fly ->
          CameraAnimation.Fly(
            duration =
              if (paceFlightBySpeed) null else flightDurationMillis.roundToInt().milliseconds,
            speed = if (paceFlightBySpeed) flightSpeed.toDouble() else null,
            minZoom = flightMinZoom.toDouble().takeIf { it > 0.0 },
          )
      }
}

@Composable fun rememberDemoSettings() = remember { DemoSettings() }

/** Settings for the platform's debug flags, frame rate cap, and render mode. */
@Composable expect fun RenderSettingsItems(settings: DemoSettings)

@Composable
fun TileLodSettingsItems(settings: DemoSettings) {
  SectionHeader("Tile level of detail")
  DropdownRow(
    label = "When the camera is pitched",
    options =
      listOf(TileLodOptions.Standard, TileLodOptions.Performance, TileLodOptions.HighDetail),
    selected = settings.renderOptions.tileLod,
    optionLabel = {
      when (it) {
        TileLodOptions.Standard -> "Standard"
        TileLodOptions.Performance -> "Fewer tiles"
        TileLodOptions.HighDetail -> "More detail"
        else -> "Custom"
      }
    },
    onSelect = { lod ->
      settings.renderOptions = RenderOptions(settings.renderOptions) { tileLod = lod }
    },
  )
}
