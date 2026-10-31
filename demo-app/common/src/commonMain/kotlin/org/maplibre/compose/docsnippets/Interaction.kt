@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.interaction.BearingTargets
import org.maplibre.compose.interaction.CameraAction
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.FeatureHit
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ModifierMatch.Containing
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
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
      },
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
      },
  )
  // #endregion bearing-snapping

  // #region bearing-haptics
  MaplibreMap(
    interactions =
      MapInteractions {
        camera {
          rotate {
            haptics {
              notch(BearingTargets.evenlySpaced(4))
              notch(BearingTargets.evenlySpaced(24), HapticFeedbackType.SegmentFrequentTick)
            }
          }
        }
      },
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
      },
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
      },
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
        GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson"),
      )
    CircleLayer(
      id = "earthquakes",
      source = earthquakes,
      interactions = {
        hitPadding = 12.dp
        onClick { event ->
          onEarthquakeSelected(event.hits.first().feature.properties)
          ClickResult.Consume
        }
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

// #region feature-rows
@Composable
fun SelectableStreets(onStreetSelected: (FeatureHit) -> Unit) {
  val state =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"))
  MaplibreMap(
    state = state,
    interactions =
      MapInteractions {
        callbacks {
          features {
            // The same layers with growing padding: a closer street wins over a higher layer.
            for (padding in listOf(0.dp, 8.dp, 16.dp)) {
              layers(
                "road_motorway",
                "road_trunk_primary",
                "road_secondary_tertiary",
                "road_minor",
              ) {
                hitPadding = padding
                onClick { event ->
                  onStreetSelected(event.hits.first())
                  ClickResult.Consume
                }
              }
            }
          }
        }
      },
  )
}
// #endregion feature-rows
