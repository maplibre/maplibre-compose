@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState

@Composable
// The application requests location permission separately.
fun Location() {
  // #region puck
  val locationProvider = rememberDefaultLocationProvider()
  val headingProvider = rememberDefaultHeadingProvider() // optional: get heading from sensors

  val locationState =
    rememberLocationState(
      provider = locationProvider,
      headingProvider = headingProvider,
    )

  val mapState = rememberMapState {
    val mapState = checkNotNull(LocalMapState.current)

    LocationPuck(
      idPrefix = "user",
      locationState = locationState,
    )

    LocationTrackingEffect(locationState = locationState) {
      mapState.animateCameraPosition(CameraPosition(target = currentLocation.position, zoom = 15.0))
    }
  }
  MaplibreMap(state = mapState)
  // #endregion puck
}
