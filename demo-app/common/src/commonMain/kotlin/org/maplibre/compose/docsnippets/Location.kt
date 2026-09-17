@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.location.rememberSystemSettingsLauncher
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.util.MaplibreComposable

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

    LocationIndicatorLayer(
      id = "user",
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

@Composable
@MaplibreComposable
private fun BearingAccuracy(locationState: LocationState) {
  // #region bearing-accuracy
  LocationIndicatorLayer(
    id = "user",
    locationState = locationState,
    bearingAccuracyRadius = interpolate(linear(), zoom(), 0 to const(32.dp), 16 to const(64.dp)),
    bearingAccuracyColor = const(Color.Blue.copy(alpha = 0.35f)),
  )
  // #endregion bearing-accuracy
}
