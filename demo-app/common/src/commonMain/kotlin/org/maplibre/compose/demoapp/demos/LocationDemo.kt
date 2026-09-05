package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoFlightDuration
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.design.ButtonRow
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SwitchRow
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
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

object LocationDemo : Demo {
  override val name = "My location"
  override val description =
    "The location puck, device heading, and a camera-follow toggle on the real device."
  override val destination = DemoDestination.None

  override val pointerPin: DemoPointerPin?
    get() = lastLocation?.let { position ->
      val delta = 0.005
      val bounds =
        BoundingBox(
          west = position.longitude - delta,
          south = position.latitude - delta,
          east = position.longitude + delta,
          north = position.latitude + delta,
        )
      DemoPointerPin(position, DemoDestination.FitBounds(bounds))
    }

  private var follow by mutableStateOf(true)
  private var engine by mutableStateOf(demoLocationEngines.first())
  private var lastLocation by mutableStateOf<Position?>(null)
  private var panelLocationState by mutableStateOf<LocationState?>(null)
  private var panelLocationBackendId by mutableStateOf<String?>(null)

  @Composable
  override fun MapContent() {
    val mapState = checkNotNull(LocalMapState.current)
    val locationProvider = engine.rememberLocationProvider()
    val locationState =
      rememberLocationState(
        provider = locationProvider,
        headingProvider = engine.rememberHeadingProvider(),
      )
    DisposableEffect(locationState, locationProvider) {
      panelLocationState = locationState
      panelLocationBackendId = locationProvider.backendId
      onDispose {
        if (panelLocationState === locationState) {
          panelLocationState = null
          panelLocationBackendId = null
        }
      }
    }
    LaunchedEffect(locationState) { locationState.requestPermission() }

    LaunchedEffect(mapState) {
      var previous = mapState.cameraMoveReason
      snapshotFlow { mapState.cameraMoveReason }
        .collect { reason ->
          // Follow moves the camera programmatically; a pan is the GESTURE that interrupts it.
          if (previous != CameraMoveReason.GESTURE && reason == CameraMoveReason.GESTURE) {
            follow = false
          }
          previous = reason
        }
    }

    val location = locationState.lastLocation
    LaunchedEffect(location) { location?.position?.let { lastLocation = it } }

    LocationTrackingEffect(locationState = locationState, enabled = follow) {
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
  override fun Panel(state: DemoAppState) {
    Text(
      text = panelLocationState?.statusMessage() ?: "Starting location",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    val settings = rememberSystemSettingsLauncher()
    val permission = panelLocationState?.permission
    if (
      permission is LocationPermission.NotGranted &&
        permission.canRequest == false &&
        settings.canOpenApplicationSettings
    ) {
      ButtonRow("Open system settings") { settings.openApplicationSettings() }
    }
    val status = panelLocationState?.status
    if (
      status is LocationTrackingStatus.Unavailable &&
        status.reason == LocationUnavailableReason.ServicesDisabled
    ) {
      if (settings.canOpenLocationServicesSettings) {
        ButtonRow("Open location settings") { settings.openLocationServicesSettings() }
      }
      ButtonRow("Retry") { panelLocationState?.retry() }
    }
    SwitchRow("Follow me", follow) { follow = it }
    if (demoLocationEngines.size > 1) {
      SegmentedRow(
        label = "Location engine",
        options = demoLocationEngines,
        selected = engine,
        optionLabel = { it.label },
        onSelect = { engine = it },
      )
      if (engine === DefaultLocationEngine) {
        panelLocationBackendId?.let { backendId ->
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
