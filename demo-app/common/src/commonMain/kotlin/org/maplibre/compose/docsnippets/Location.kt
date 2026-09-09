@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.location.rememberSystemSettingsLauncher
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

@Composable
private fun LocationPermissionButton(locationState: LocationState) {
  // #region permission
  if (locationState.permission !is LocationPermission.Granted) {
    Button(onClick = locationState::requestPermission) {
      Text("Use my location")
    }
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
        Button(onClick = locationState::requestPermission) { Text("Use my location") }
      settings.canOpenApplicationSettings ->
        Button(onClick = { settings.openApplicationSettings() }) { Text("Open settings") }
    }
  }
  // #endregion permission-settings
}
