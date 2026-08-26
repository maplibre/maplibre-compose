@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.mostAccurateBearing
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberDefaultOrientationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState

@Composable
@OptIn(ExperimentalResourceApi::class)
// The snippet below shows the calls alone. Requesting the permission belongs to the surrounding
// app, which the documentation covers in prose.
fun Location() {
  // #region puck
  val locationProvider = rememberDefaultLocationProvider()
  val orientationProvider =
    rememberDefaultOrientationProvider() // optional: get device orientation from sensors
  val locationState =
    rememberLocationState(
      provider = locationProvider,
      orientationProvider = orientationProvider,
    )

  val map = rememberMapState {
    // The style content receives the map state as its receiver.
    LocationPuck(
      idPrefix = "user",
      location = locationState.location,
      // optional: combine course and orientation bearing
      bearing = locationState.mostAccurateBearing(),
      state = this,
    )

    LocationTrackingEffect(locationState = locationState) {
      animateCamera(CameraPosition(target = currentLocation.position.value, zoom = 15.0))
    }
  }
  MaplibreMap(map)
  // #endregion puck
}
