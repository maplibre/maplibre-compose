@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@Composable
fun Camera() {
  // #region first-position
  val mapState =
    rememberMapState(
      initialCameraPosition =
        CameraPosition(target = Position(latitude = 45.521, longitude = -122.675), zoom = 13.0)
    )
  MaplibreMap(state = mapState)
  // #endregion first-position

  // #region animate
  LaunchedEffect(mapState) {
    mapState.animateCameraPosition(
      position =
        mapState.cameraPosition.copy(target = Position(latitude = 47.607, longitude = -122.342))
    )
  }
  // #endregion animate

  // #region animate-fly
  LaunchedEffect(mapState) {
    mapState.animateCameraPosition(
      position =
        CameraPosition(target = Position(latitude = 40.713, longitude = -74.006), zoom = 12.0),
      animation = CameraAnimation.Fly(duration = 3.seconds, minZoom = 4.0),
    )
  }
  // #endregion animate-fly

  // #region animate-ease
  LaunchedEffect(mapState) {
    mapState.animateCameraPosition(
      position = mapState.cameraPosition.copy(zoom = mapState.cameraPosition.zoom + 1.0),
      animation = CameraAnimation.Ease(duration = 500.milliseconds),
    )
  }
  // #endregion animate-ease

  // #region animate-around
  LaunchedEffect(mapState) {
    mapState.animateCameraAround(
      anchor = CameraAnchor.Screen(DpOffset(120.dp, 200.dp)),
      zoom = 16.0,
      bearing = 90.0,
      animation = CameraAnimation.Ease(500.milliseconds),
    )
  }
  // #endregion animate-around

  // #region fit-bounds
  LaunchedEffect(mapState) {
    mapState.animateCameraToBounds(
      boundingBox = BoundingBox(west = -123.0, south = 47.0, east = -122.0, north = 48.0),
      padding = PaddingValues(32.dp),
    )
  }
  // #endregion fit-bounds

  // #region camera-for-bounds
  LaunchedEffect(mapState) {
    val camera =
      mapState.cameraForBounds(
        boundingBox = BoundingBox(west = -123.0, south = 47.0, east = -122.0, north = 48.0),
        padding = PaddingValues(32.dp),
      )
    mapState.animateCameraPosition(camera.copy(zoom = minOf(camera.zoom, 12.0)))
  }
  // #endregion camera-for-bounds

  // #region viewport
  val viewport = mapState.viewport
  if (viewport != null) {
    Text("Visible bounds: ${viewport.visibleBounds}")
  }
  // #endregion viewport

  // #region convert
  val screenOffset = mapState.screenLocationFromPosition(mapState.cameraPosition.target)
  val geoPosition = mapState.positionFromScreenLocation(DpOffset(x = 100.dp, y = 150.dp))
  // #endregion convert
}
