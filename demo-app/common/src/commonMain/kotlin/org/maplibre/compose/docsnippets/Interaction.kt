@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ModifierMatch.Containing
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.spatialk.geojson.Position

@Composable
fun Interaction() {
  // #region camera-movement
  MaplibreMap(
    interactions =
      MapInteractions {
        camera {
          rotate { enabled = false }
          tilt { enabled = false }
        }
      }
  )
  // #endregion camera-movement

  // #region scroll-mappings
  MaplibreMap(
    interactions =
      MapInteractions {
        bindings {
          scroll {
            mappings {
              on(modifiers = Containing(KeyModifier.Ctrl)) { zoom() }
              otherwise { pan() }
            }
          }
        }
      }
  )
  // #endregion scroll-mappings
}

// #region map-click
@Composable
fun ClickableMap(onLocationSelected: (Position) -> Unit) {
  MaplibreMap(
    interactions =
      MapInteractions {
        callbacks {
          click {
            onEvent { event ->
              event.position?.let(onLocationSelected)
              ClickResult.Consume
            }
          }
        }
      }
  )
}
// #endregion map-click
