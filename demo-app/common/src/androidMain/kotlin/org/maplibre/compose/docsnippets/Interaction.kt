@file:Suppress("unused", "UNUSED_ANONYMOUS_PARAMETER")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.toJson

@Composable
fun Interaction() {
  // #region common-gestures
  MaplibreMap(rememberMapState(), options = MapOptions(gestureOptions = GestureOptions.Standard))
  // #endregion common-gestures

  // #region gesture-settings
  MaplibreMap(
    rememberMapState(),
    options =
      MapOptions(
        gestureOptions =
          GestureOptions(
            isTwoFingerTiltEnabled = true,
            isPinchZoomEnabled = true,
            isTwoFingerRotateEnabled = true,
            isDragPanEnabled = true,
          )
      ),
  )
  // #endregion gesture-settings

  val map = rememberMapState()

  // #region click-listeners
  val scope = rememberCoroutineScope()
  MaplibreMap(
    map,
    onMapClick = { pos, offset ->
      scope.launch {
        val features = map.queryRenderedFeatures(offset)
        if (features.isNotEmpty()) {
          println("Clicked on ${features[0].toJson()}")
        }
      }
      ClickResult.Pass
    },
    onMapLongClick = { pos, offset ->
      println("Long click at $pos")
      ClickResult.Pass
    },
  )
  // #endregion click-listeners
}
