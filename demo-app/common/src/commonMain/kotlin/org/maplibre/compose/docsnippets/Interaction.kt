@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.interaction.BearingTargets
import org.maplibre.compose.interaction.CameraAction
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.HapticEmphasis
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ModifierMatch.Containing
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Position

@Composable
fun Interaction() {
  // #region camera-movement
  MaplibreMap(
    interactions =
      MapInteractions {
        camera {
          rotate { enabled = false }
          pitch { enabled = false }
        }
      }
  )
  // #endregion camera-movement

  // #region bearing-snapping
  MaplibreMap(
    interactions =
      MapInteractions {
        camera {
          rotate {
            snapping {
              targets = BearingTargets.evenlySpaced(count = 4)
              tolerance = 7.0
            }
          }
        }
      }
  )
  // #endregion bearing-snapping

  // #region bearing-haptics
  MaplibreMap(
    interactions =
      MapInteractions {
        camera {
          rotate {
            haptics {
              notch(BearingTargets.evenlySpaced(24), HapticEmphasis.Subtle)
              notch(BearingTargets.evenlySpaced(4), HapticEmphasis.Standard)
              notch(BearingTargets.at(0.0), HapticEmphasis.Emphasized)
            }
          }
        }
      }
  )
  // #endregion bearing-haptics

  // #region scroll-mappings
  MaplibreMap(
    uiOptions =
      MapUiOptions {
        bindings {
          scroll {
            mappings {
              on(modifiers = Containing(KeyModifier.Ctrl), action = CameraAction.Zoom)
              otherwise(CameraAction.Pan)
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

// #region pan-start
@Composable
fun TrackingMap(onUserPanned: () -> Unit) {
  MaplibreMap(interactions = MapInteractions { camera { pan { onStart(onUserPanned) } } })
}

// #endregion pan-start

// #region layer-click
@Composable
fun SelectableEarthquakes(onEarthquakeSelected: (JsonObject?) -> Unit) {
  val state = rememberMapState {
    val earthquakes =
      rememberGeoJsonSource(
        GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson")
      )
    CircleLayer(
      id = "earthquakes",
      source = earthquakes,
      hitPadding = 12.dp,
      onClick = { features ->
        onEarthquakeSelected(features.first().properties)
        ClickResult.Consume
      },
    )
  }
  MaplibreMap(
    state = state,
    interactions =
      MapInteractions {
        callbacks {
          click {
            onUnhandled {
              onEarthquakeSelected(null)
              ClickResult.Pass
            }
          }
        }
      },
  )
}
// #endregion layer-click
