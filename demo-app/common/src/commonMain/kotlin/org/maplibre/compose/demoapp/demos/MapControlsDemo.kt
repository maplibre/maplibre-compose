package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.design.DropdownRow
import org.maplibre.compose.map.KeyModifier
import org.maplibre.compose.map.MapInteractions
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.ModifierMatch.Containing
import org.maplibre.spatialk.geojson.Position

object MapControlsDemo : Demo {
  override val name = "Map controls"
  override val description = "Try different ways to navigate the map."
  override val destination =
    DemoDestination.ExactCamera(CameraPosition(target = Position(-122.3352, 47.6205), zoom = 14.0))

  private enum class Recipe(
    val title: String,
    val explanation: String,
    val interactions: MapInteractions,
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
      MapInteractions {
        bindings {
          scroll {
            mappings {
              on(modifiers = Containing(KeyModifier.Ctrl)) { zoom() }
              otherwise { pan() }
            }
          }
        }
      },
    ),
  }

  private var recipe by mutableStateOf(Recipe.Standard)

  override fun interactions(mapState: MapState): MapInteractions = recipe.interactions

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
  }
}
