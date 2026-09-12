package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.design.ButtonRow
import org.maplibre.compose.demoapp.design.DropdownRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ModifierMatch.Containing
import org.maplibre.compose.interaction.ScrollResponse
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.spatialk.geojson.Position

object MapControlsDemo : Demo {
  override val name = "Map controls"
  override val description = "Try different ways to navigate the map."
  override val destination =
    DemoDestination.ExactCamera(CameraPosition(target = Position(-122.3352, 47.6205), zoom = 14.0))

  private enum class Recipe(
    val title: String,
    val explanation: String,
    val interactions: MapInteractions = MapInteractions.Standard,
    val bindings: (MapUiOptions) -> MapUiOptions = { it },
  ) {
    Standard(
      "Standard navigation",
      "Explore with the usual pan, zoom, rotation, and tilt controls. Vertical scrolling zooms.",
      MapInteractions.Standard,
    ),
    NorthUp(
      "North-up map",
      "Pan and zoom freely. Rotation and tilt are disabled across input methods.",
      MapInteractions {
        camera {
          rotate { enabled = false }
          tilt { enabled = false }
        }
      },
    ),
    FixedLocation(
      "Zoom around a fixed location",
      "Zoom in and out while keeping the camera target fixed. Panning, rotation, and tilt are disabled.",
      MapInteractions {
        camera {
          pan { enabled = false }
          rotate { enabled = false }
          tilt { enabled = false }
        }
      },
    ),
    ScrollPan(
      "Scroll to pan",
      "Use a wheel or trackpad to pan. Hold Ctrl while scrolling vertically to zoom. Touch controls stay standard.",
      bindings = { settings ->
        MapUiOptions(settings) {
          bindings {
            scroll {
              mappings {
                on(modifiers = Containing(KeyModifier.Ctrl), response = ScrollResponse.Zoom)
                otherwise(ScrollResponse.Pan)
              }
            }
          }
        }
      },
    ),
  }

  private var recipe by mutableStateOf(Recipe.Standard)

  private enum class Transition(val title: String) {
    Fly("Fly"),
    Ease("Ease"),
  }

  private enum class City(val title: String, val camera: CameraPosition) {
    Seattle("Seattle", CameraPosition(target = Position(-122.3352, 47.6205), zoom = 14.0)),
    NewYork("New York", CameraPosition(target = Position(-74.006, 40.7128), zoom = 13.0)),
    London("London", CameraPosition(target = Position(-0.1276, 51.5072), zoom = 12.0)),
  }

  private var transition by mutableStateOf(Transition.Fly)
  private var paceBySpeed by mutableStateOf(true)
  private var durationMillis by mutableStateOf(2000f)
  private var speed by mutableStateOf(CameraAnimation.Fly.DefaultSpeed.toFloat())
  /** Zero means no limit. */
  private var minZoom by mutableStateOf(0f)

  private val animation: CameraAnimation
    get() =
      when (transition) {
        Transition.Ease -> CameraAnimation.Ease(durationMillis.roundToInt().milliseconds)
        Transition.Fly ->
          CameraAnimation.Fly(
            duration = if (paceBySpeed) null else durationMillis.roundToInt().milliseconds,
            speed = if (paceBySpeed) speed.toDouble() else null,
            minZoom = minZoom.toDouble().takeIf { it > 0.0 },
          )
      }

  override fun interactions(mapState: MapState): MapInteractions = recipe.interactions

  override fun uiOptions(settings: MapUiOptions): MapUiOptions = recipe.bindings(settings)

  @Composable
  override fun Panel(state: DemoAppState) {
    DropdownRow(
      label = "Recipe",
      options = Recipe.entries,
      selected = recipe,
      optionLabel = { it.title },
      onSelect = {
        recipe = it
        if (it == Recipe.NorthUp || it == Recipe.FixedLocation) {
          state.mapState.setCameraPosition(
            state.mapState.cameraPosition.copy(bearing = 0.0, tilt = 0.0)
          )
        }
      },
    )
    Text(
      recipe.explanation,
      modifier = Modifier.padding(16.dp),
      style = MaterialTheme.typography.bodyMedium,
    )

    SectionHeader("Camera animation")
    SegmentedRow(
      options = Transition.entries,
      selected = transition,
      optionLabel = { it.title },
      onSelect = { transition = it },
    )
    if (transition == Transition.Fly) {
      SwitchRow("Pace by speed", checked = paceBySpeed) { paceBySpeed = it }
    }
    if (transition == Transition.Fly && paceBySpeed) {
      SliderRow(
        label = "Speed",
        value = speed,
        range = 0.5f..10f,
        valueLabel = { "${(it * 10).roundToInt() / 10f} screens/s" },
        onChange = { speed = it },
      )
    } else {
      SliderRow(
        label = "Duration",
        value = durationMillis,
        range = 200f..5000f,
        valueLabel = { "${it.roundToInt()} ms" },
        onChange = { durationMillis = it },
      )
    }
    if (transition == Transition.Fly) {
      SliderRow(
        label = "Minimum zoom",
        value = minZoom,
        range = 0f..12f,
        valueLabel = { if (it > 0f) it.roundToInt().toString() else "None" },
        onChange = { minZoom = it.roundToInt().toFloat() },
      )
    }
    val scope = rememberCoroutineScope()
    val mapState = state.mapState
    for (city in City.entries) {
      ButtonRow("Go to ${city.title}") {
        scope.launch { mapState.animateCameraPosition(city.camera, animation) }
      }
    }
    ButtonRow("Turn 90°") {
      scope.launch {
        val camera = mapState.cameraPosition
        mapState.animateCameraPosition(camera.copy(bearing = camera.bearing + 90.0), animation)
      }
    }
  }
}
