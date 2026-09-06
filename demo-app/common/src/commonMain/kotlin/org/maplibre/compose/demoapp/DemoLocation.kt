package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.demos.DefaultLocationEngine
import org.maplibre.compose.demoapp.demos.demoLocationEngines
import org.maplibre.compose.demoapp.design.ButtonRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.LocationTrackingStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.location.rememberSystemSettingsLauncher
import org.maplibre.compose.location.updateCamera
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.material3.LocationPuckDefaults
import org.maplibre.compose.util.MaplibreComposable

/** Follow mode, engine choice, and the active [LocationState] for the shared map. */
@Stable
internal class DemoLocationUi {
  var follow by mutableStateOf(false)
  var engine by mutableStateOf(demoLocationEngines.first())
  var locationState by mutableStateOf<LocationState?>(null)
    internal set

  var backendId by mutableStateOf<String?>(null)
    internal set

  fun toggleFollow() {
    follow = !follow
    if (follow) locationState?.requestPermission()
  }
}

/**
 * The location puck and camera follow on the shared map. Permission is requested when the user
 * turns follow on, not when the map first composes.
 */
@MaplibreComposable
@Composable
internal fun DemoLocationMapContent(location: DemoLocationUi) {
  val mapState = checkNotNull(LocalMapState.current)
  val engine = location.engine
  val locationProvider = engine.rememberLocationProvider()
  val locationState =
    rememberLocationState(
      provider = locationProvider,
      headingProvider = engine.rememberHeadingProvider(),
    )
  DisposableEffect(locationState, locationProvider) {
    location.locationState = locationState
    location.backendId = locationProvider.backendId
    onDispose {
      if (location.locationState === locationState) {
        location.locationState = null
        location.backendId = null
      }
    }
  }

  LaunchedEffect(mapState) {
    var previous = mapState.cameraMoveReason
    snapshotFlow { mapState.cameraMoveReason }
      .collect { reason ->
        if (previous != CameraMoveReason.GESTURE && reason == CameraMoveReason.GESTURE) {
          location.follow = false
        }
        previous = reason
      }
  }

  LocationTrackingEffect(locationState = locationState, enabled = location.follow) {
    if (previousLocation == null) {
      mapState.animateCameraPosition(
        CameraPosition(target = currentLocation.position, zoom = 16.0),
        duration = DemoFlightDuration,
      )
    } else {
      updateCamera(mapState)
    }
  }

  LocationPuck(
    idPrefix = "user",
    locationState = locationState,
    colors = LocationPuckDefaults.colors(),
  )
}

@Composable
internal fun LocationSettingsItems(location: DemoLocationUi) {
  SectionHeader("Location")
  Text(
    text = location.locationState?.statusMessage() ?: "Location is off",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
  )
  val settings = rememberSystemSettingsLauncher()
  val permission = location.locationState?.permission
  if (
    permission is LocationPermission.NotGranted &&
      permission.canRequest == false &&
      settings.canOpenApplicationSettings
  ) {
    ButtonRow("Open system settings") { settings.openApplicationSettings() }
  }
  val status = location.locationState?.status
  if (
    status is LocationTrackingStatus.Unavailable &&
      status.reason == LocationUnavailableReason.ServicesDisabled
  ) {
    if (settings.canOpenLocationServicesSettings) {
      ButtonRow("Open location settings") { settings.openLocationServicesSettings() }
    }
    ButtonRow("Retry") { location.locationState?.retry() }
  }
  if (demoLocationEngines.size > 1) {
    SegmentedRow(
      label = "Location engine",
      options = demoLocationEngines,
      selected = location.engine,
      optionLabel = { it.label },
      onSelect = { location.engine = it },
    )
    if (location.engine === DefaultLocationEngine) {
      location.backendId?.let { backendId ->
        Text(
          text = "Selected provider: $backendId",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }
    }
  }
}

private fun LocationState.statusMessage(): String {
  val permission = permission as? LocationPermission.NotGranted
  return when {
    availability == LocationBackendAvailability.Unsupported ->
      "Location is not available on this device"
    availability is LocationBackendAvailability.Misconfigured ->
      "Location is misconfigured on this device"
    permission?.canRequest == false ->
      "Location permission was denied; turn it on in the system settings"
    permission != null -> "Waiting for location permission"
    else -> trackingStatusMessage()
  }
}

private fun LocationState.trackingStatusMessage(): String =
  when (val status = status) {
    LocationTrackingStatus.Stopped -> "Location is off"
    LocationTrackingStatus.Starting -> "Finding your location"
    LocationTrackingStatus.Tracking -> "Tracking your location"
    is LocationTrackingStatus.Unavailable ->
      when (status.reason) {
        LocationUnavailableReason.ServicesDisabled -> "Location services are turned off"
        LocationUnavailableReason.TemporarilyUnavailable -> "Location is temporarily unavailable"
        LocationUnavailableReason.Unsupported -> "Location is not available on this device"
        LocationUnavailableReason.PermissionDenied -> "Location permission was denied"
        LocationUnavailableReason.UnexpectedFailure -> "Location failed"
      }
  }
