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
import org.maplibre.compose.map.MapGestures
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.toJson

@Composable
fun Interaction() {
  // #region common-gestures
  MaplibreMap(gestures = MapGestures.Standard)
  // #endregion common-gestures

  // #region gesture-settings
  MaplibreMap(
    gestures =
      MapGestures {
        twoFingerTilt { enabled = false }
        pinchZoom { startSpanSlop = 10.dp }
        dragPan { continuation = null }
      }
  )
  // #endregion gesture-settings

  // #region pan-observer
  var following by remember { mutableStateOf(true) }
  MaplibreMap(gestures = MapGestures { dragPan { onStart { following = false } } })
  // #endregion pan-observer

  val mapState = rememberMapState()

  // #region click-listeners
  val scope = rememberCoroutineScope()
  MaplibreMap(
    state = mapState,
    onClick = { pos, offset ->
      scope.launch {
        val features = mapState.queryRenderedFeatures(offset)
        if (features.isNotEmpty()) {
          println("Clicked on ${features[0].toJson()}")
        }
      }
      ClickResult.Consume
    },
    onLongClick = { pos, offset ->
      println("Long click at $pos")
      ClickResult.Pass
    },
  )
  // #endregion click-listeners
}

// #region gesture-camera
suspend fun moveWithGesture(mapState: MapState) {
  mapState.gestureCamera.withGesture {
    moveBy(deltaX = 40.0, deltaY = 0.0)
    scaleBy(scale = 1.5)
  }
}
// #endregion gesture-camera
