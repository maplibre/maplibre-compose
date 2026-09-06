@file:Suppress("unused", "UNUSED_ANONYMOUS_PARAMETER")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.maplibre.compose.map.KeyModifier
import org.maplibre.compose.map.MapInteractions
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.ModifierMatch.Containing
import org.maplibre.compose.map.ScrollKind
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.map.withCameraInput
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.toJson

@Composable
fun Interaction() {
  // #region common-interactions
  MaplibreMap(interactions = MapInteractions.Standard)
  // #endregion common-interactions

  // #region interaction-settings
  MaplibreMap(
    interactions =
      MapInteractions {
        camera {
          tilt { enabled = false }
          pan { momentum { enabled = false } }
        }
        bindings { transform { zoom { startSpanSlop = 10.dp } } }
      }
  )
  // #endregion interaction-settings

  // #region scroll-mappings
  MaplibreMap(
    interactions =
      MapInteractions {
        bindings {
          scroll {
            mappings {
              on(modifiers = Containing(KeyModifier.Ctrl)) { zoom() }
              on(kind = ScrollKind.Continuous) { pan() }
              otherwise { zoom() }
            }
          }
        }
      }
  )
  // #endregion scroll-mappings

  // #region pan-observer
  var following by remember { mutableStateOf(true) }
  MaplibreMap(interactions = MapInteractions { camera { pan { onStart { following = false } } } })
  // #endregion pan-observer

  val mapState = rememberMapState()

  // #region click-listeners
  val scope = rememberCoroutineScope()
  MaplibreMap(
    state = mapState,
    interactions =
      MapInteractions {
        callbacks {
          click {
            onEvent { event ->
              scope.launch {
                val features = mapState.queryRenderedFeatures(event.screenOffset)
                if (features.isNotEmpty()) println("Clicked on ${features[0].toJson()}")
              }
              ClickResult.Consume
            }
          }
          contextClick {
            onEvent { event ->
              println("Context action at ${event.position}")
              ClickResult.Pass
            }
          }
        }
      },
  )
  // #endregion click-listeners
}

// #region camera-input
suspend fun moveWithController(mapState: MapState) {
  mapState.withCameraInput {
    panBy(deltaX = 40.0, deltaY = 0.0)
    scaleBy(scale = 1.5)
  }
}
// #endregion camera-input
