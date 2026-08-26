@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@Composable
fun Camera() {
  // #region first-position
  val map =
    rememberMapState(
      cameraPosition =
        CameraPosition(target = Position(latitude = 45.521, longitude = -122.675), zoom = 13.0)
    )
  MaplibreMap(map)
  // #endregion first-position

  // #region animate
  LaunchedEffect(Unit) {
    map.animateCamera(
      position = map.camera.copy(target = Position(latitude = 47.607, longitude = -122.342)),
      duration = 3.seconds,
    )
  }
  // #endregion animate

  // #region fit-bounds
  LaunchedEffect(Unit) {
    map.animateCamera(
      boundingBox = BoundingBox(west = -123.0, south = 47.0, east = -122.0, north = 48.0),
      padding = PaddingValues(32.dp),
    )
  }
  // #endregion fit-bounds

  // #region viewport
  val viewport = map.viewport
  if (viewport != null) {
    Text("Visible bounds: ${viewport.visibleBoundingBox}")
  }
  // #endregion viewport

  // #region convert
  if (map.viewport != null) {
    val screenOffset = map.screenLocationFromPosition(map.camera.target)
    val geoPosition = map.positionFromScreenLocation(DpOffset(x = 100.dp, y = 150.dp))
  }
  // #endregion convert
}
