@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.location.HeadingRequest
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.LocationTrackingStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.location.rememberSystemSettingsLauncher
import org.maplibre.compose.location.updateCamera
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState

@Composable
// The application requests location permission separately.
fun Location() {
  // #region puck
  val locationState =
    rememberLocationState(
      provider = rememberDefaultLocationProvider(),
      headingProvider = rememberDefaultHeadingProvider(),
    )

  val mapState = rememberMapState {
    LocationIndicatorLayer(id = "user", locationState = locationState)
  }

  LocationTrackingEffect(locationState = locationState) {
    if (previousLocation == null) {
      // First location: zoom in on the user.
      mapState.animateCamera(CameraUpdate(center = currentLocation.position, zoom = 15.0))
    } else {
      updateCamera(mapState)
    }
  }

  MaplibreMap(state = mapState)
  // #endregion puck
}

@Composable
private fun LocationPermissionButton(locationState: LocationState) {
  // #region permission
  if (locationState.permission !is LocationPermission.Granted) {
    Button(onClick = locationState::requestPermission) { Text("Show my location") }
  }
  // #endregion permission
}

@Composable
private fun LocationPermissionSettings(locationState: LocationState) {
  // #region permission-settings
  val settings = rememberSystemSettingsLauncher()
  val permission = locationState.permission
  if (permission is LocationPermission.NotGranted) {
    when {
      permission.shouldShowRationale -> {
        Text("Your location helps you find places nearby.")
        Button(onClick = locationState::requestPermission) { Text("Continue") }
      }
      permission.canRequest != false ->
        Button(onClick = locationState::requestPermission) { Text("Show my location") }
      settings.canOpenApplicationSettings ->
        Button(onClick = { settings.openApplicationSettings() }) { Text("Open settings") }
    }
  }
  // #endregion permission-settings
}

@Composable
private fun LocationStatusMessage(locationState: LocationState) {
  // #region status
  val settings = rememberSystemSettingsLauncher()
  if (locationState.availability != LocationBackendAvailability.Available) {
    Text("Location is not available on this device.")
  }
  val status = locationState.status
  if (status is LocationTrackingStatus.Unavailable) {
    if (status.reason == LocationUnavailableReason.ServicesDisabled) {
      Text("Turn on location services to see your position.")
      if (settings.canOpenLocationServicesSettings) {
        Button(onClick = { settings.openLocationServicesSettings() }) { Text("Open settings") }
      }
    } else {
      Text("Your location is not available right now.")
    }
    Button(onClick = locationState::retry) { Text("Try again") }
  }
  // #endregion status
}

@Composable
private fun LocationRequests() {
  val provider = rememberDefaultLocationProvider()
  val headingProvider = rememberDefaultHeadingProvider()
  // #region requests
  val locationState =
    rememberLocationState(
      provider = provider,
      request =
        LocationRequest {
          accuracy = LocationAccuracy.Balanced
          minimumInterval = 5.seconds
        },
      headingProvider = headingProvider,
      headingRequest = HeadingRequest { minimumInterval = 2.seconds },
    )
  // #endregion requests
}
