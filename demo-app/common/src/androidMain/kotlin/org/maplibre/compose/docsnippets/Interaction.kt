@file:Suppress("unused", "UNUSED_ANONYMOUS_PARAMETER")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

@Composable
fun Interaction() {
  // #region common-gestures
  MaplibreMap(options = MapOptions(gestureOptions = GestureOptions.Standard))
  // #endregion common-gestures

  // #region gesture-settings
  MaplibreMap(
    options =
      MapOptions(
        gestureOptions =
          GestureOptions(
            isTiltEnabled = true,
            isZoomEnabled = true,
            isRotateEnabled = true,
            isScrollEnabled = true,
          )
      )
  )
  // #endregion gesture-settings

  // #region camera
  val camera =
    rememberCameraState(
      firstPosition =
        CameraPosition(target = Position(latitude = 45.521, longitude = -122.675), zoom = 13.0)
    )
  MaplibreMap(cameraState = camera)
  // #endregion camera

  // #region camera-animate
  LaunchedEffect(Unit) {
    camera.animateTo(
      finalPosition =
        camera.position.copy(target = Position(latitude = 47.607, longitude = -122.342)),
      duration = 3.seconds,
    )
  }
  // #endregion camera-animate

  // #region click-listeners
  MaplibreMap(
    cameraState = camera,
    onMapClick = { pos, offset ->
      val features = camera.projection?.queryRenderedFeatures(offset)
      if (!features.isNullOrEmpty()) {
        println("Clicked on ${features[0].toJson()}")
        ClickResult.Consume // (1)!
      } else {
        ClickResult.Pass
      }
    },
    onMapLongClick = { pos, offset ->
      println("Long click at $pos")
      ClickResult.Pass
    },
  )
  // #endregion click-listeners
}
