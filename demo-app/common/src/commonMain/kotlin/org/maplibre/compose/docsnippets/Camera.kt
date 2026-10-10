@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@Composable
fun Camera() {
  // #region first-position
  val mapState =
    rememberMapState(
      initialCameraPosition =
        CameraPosition(center = Position(latitude = 45.521, longitude = -122.675), zoom = 13.0)
    )
  MaplibreMap(state = mapState)
  // #endregion first-position
}

@Composable
fun ZoomInButton(mapState: MapState) {
  // #region animate
  val scope = rememberCoroutineScope()
  Button(
    onClick = {
      scope.launch {
        mapState.animateCamera(CameraUpdate(zoom = mapState.cameraPosition.zoom + 1.0))
      }
    }
  ) {
    Text("Zoom in")
  }
  // #endregion animate
}

@Composable
fun FlyToCity(mapState: MapState, city: Position) {
  // #region animate-fly
  LaunchedEffect(city) {
    mapState.animateCamera(
      update = CameraUpdate(center = city, zoom = 12.0),
      animation = CameraAnimation.Fly { duration = 3.seconds },
    )
  }
  // #endregion animate-fly
}

// #region set-position
fun showPlace(mapState: MapState, place: Position) {
  mapState.setCameraPosition(mapState.cameraPosition.copy(center = place, zoom = 15.0))
}

// #endregion set-position

// #region animate-around
suspend fun zoomInOn(mapState: MapState, place: Position) {
  mapState.animateCameraAround(
    anchor = CameraAnchor.Geographic(place),
    zoom = mapState.cameraPosition.zoom + 2.0,
  )
}

// #endregion animate-around

// #region fit-bounds
suspend fun showRoute(mapState: MapState, routeBounds: BoundingBox) {
  mapState.animateCameraToBounds(
    boundingBox = routeBounds,
    fit =
      CameraFit(fitPadding = DpPadding(left = 32.dp, top = 32.dp, right = 32.dp, bottom = 32.dp)),
  )
}

// #endregion fit-bounds

// #region camera-for-bounds
suspend fun showResults(mapState: MapState, resultBounds: BoundingBox) {
  val camera = mapState.cameraForBounds(resultBounds)
  mapState.animateCamera(
    update = camera.copy(zoom = minOf(camera.zoom, 15.0)).toCameraUpdate(),
    animation = CameraAnimation.Fly.Standard,
  )
}

// #endregion camera-for-bounds

@Composable
fun VisibleArea(mapState: MapState) {
  // #region viewport
  val viewport = mapState.viewport
  if (viewport != null) {
    Text("Zoom: ${viewport.cameraPosition.zoom}")
    Text("Visible bounds: ${viewport.visibleBounds}")
  }
  // #endregion viewport
}

fun convertCoordinates(mapState: MapState) {
  // #region convert
  val centerOnScreen: DpOffset? =
    mapState.screenLocationFromPosition(mapState.cameraPosition.center)
  val positionAtPoint: Position? =
    mapState.positionFromScreenLocation(DpOffset(x = 100.dp, y = 150.dp))
  // #endregion convert
}

@Composable
fun CityMap(mapState: MapState) {
  // #region constraints
  MaplibreMap(
    state = mapState,
    cameraConstraints =
      CameraConstraints {
        minZoom = 10.0
        maxZoom = 18.0
        maxPitch = 0.0
        boundingBox = BoundingBox(west = -122.84, south = 45.43, east = -122.47, north = 45.65)
      },
  )
  // #endregion constraints
}
