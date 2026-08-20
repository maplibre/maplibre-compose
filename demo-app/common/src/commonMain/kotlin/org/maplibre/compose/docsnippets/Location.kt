@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.mostAccurateBearing
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberDefaultOrientationProvider
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
  val orientationProvider =
    rememberDefaultOrientationProvider() // optional: get device orientation from sensors
  val locationState =
    rememberLocationState(
      provider = locationProvider,
      orientationProvider = orientationProvider,
    )

  MaplibreMap(cameraState = cameraState) {
    LocationPuck(
      idPrefix = "user",
      location = locationState.location,
      // optional: combine course and orientation bearing
      bearing = locationState.mostAccurateBearing(),
      cameraState = cameraState,
    )

    LocationTrackingEffect(locationState = locationState) {
      cameraState.animateTo(CameraPosition(target = currentLocation.position.value, zoom = 15.0))
    }
  }
  // #endregion puck
}

// #region custom-provider
class ReplayLocationProvider(private val locations: List<Location>) : LocationProvider {
  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    locations.forEach { emit(LocationEvent.Fix(it)) }
    emit(LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable))
  }
}
// #endregion custom-provider
