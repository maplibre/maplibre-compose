@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.mostAccurateBearing
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberDefaultOrientationProvider
import org.maplibre.compose.location.rememberUserLocationState
import org.maplibre.compose.map.MaplibreMap

@Composable
@OptIn(ExperimentalResourceApi::class)
fun Location() {
  // -8<- [start:puck]
  val cameraState = rememberCameraState()

  val locationProvider = rememberDefaultLocationProvider()
  val orientationProvider =
    rememberDefaultOrientationProvider() // optional: get device orientation from sensors
  val locationState = rememberUserLocationState(locationProvider, orientationProvider)

  MaplibreMap(cameraState = cameraState) {
    LocationPuck(
      idPrefix = "user",
      location = locationState.location,
      // optional: combine course and orientation bearing
      bearing = locationState.mostAccurateBearing(),
      cameraState = cameraState,
    )

    LocationTrackingEffect(locationState = locationState) {
      val position = currentLocation.location?.position?.value
      if (position != null) {
        cameraState.animateTo(CameraPosition(target = position, zoom = 15.0))
      }
    }
  }
  // -8<- [end:puck]
}
