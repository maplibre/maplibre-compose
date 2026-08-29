@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.mostAccurateBearing
import org.maplibre.compose.location.mostAccurateBearingAccuracy
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MaplibreMap

@Composable
@OptIn(ExperimentalResourceApi::class)
// The snippet below shows the calls alone. Requesting the permission belongs to the surrounding
// app, which the documentation covers in prose.
fun Location() {
  // #region puck
  val cameraState = rememberCameraState()

  val locationProvider = rememberDefaultLocationProvider()
  val headingProvider = rememberDefaultHeadingProvider() // optional: get heading from sensors
  val locationState =
    rememberLocationState(
      provider = locationProvider,
      headingProvider = headingProvider,
    )

  MaplibreMap(cameraState = cameraState) {
    LocationPuck(
      idPrefix = "user",
      location = locationState.lastFix,
      measurementMark = locationState.lastFixMeasurementMark,
      // optional: combine the travel course and device heading
      bearing = locationState.mostAccurateBearing(),
      bearingAccuracy = locationState.mostAccurateBearingAccuracy(),
      cameraState = cameraState,
    )

    LocationTrackingEffect(locationState = locationState) {
      cameraState.animateTo(CameraPosition(target = currentFix.position, zoom = 15.0))
    }
  }
  // #endregion puck
}
