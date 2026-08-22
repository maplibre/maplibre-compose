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
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoFlightDuration
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.compose.demoapp.design.ButtonRow
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.LocationTrackingStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.mostAccurateBearing
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.location.rememberSystemSettingsLauncher
import org.maplibre.compose.location.updateCamera
import org.maplibre.compose.material3.LocationPuckDefaults
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

object LocationDemo : Demo {
  override val name = "My location"
  override val description =
    "The location puck, device heading, and a camera-follow toggle on the real device."
  override val preferredStyle = OpenFreeMap.Liberty
  override val fliesOnSelect = false
  override val showsPointerPin
    get() = lastFix != null

  override val region: BoundingBox
    get() {
      val p = lastFix ?: Position(longitude = 0.0, latitude = 0.0)
      val delta = 0.005
      return BoundingBox(
        west = p.longitude - delta,
        south = p.latitude - delta,
        east = p.longitude + delta,
        north = p.latitude + delta,
      )
    }

  private var follow by mutableStateOf(true)
  private var engine by mutableStateOf(demoLocationEngines.first())
  private var useNativeIndicator by mutableStateOf(false)
  private var lastFix by mutableStateOf<Position?>(null)
  private var panelLocationState by mutableStateOf<LocationState?>(null)

  @Composable
  override fun MapContent(cameraState: CameraState) {
    val locationState =
      rememberLocationState(
        provider = engine.rememberLocationProvider(),
        orientationProvider = engine.rememberOrientationProvider(),
      )
    DisposableEffect(locationState) {
      panelLocationState = locationState
      onDispose { if (panelLocationState === locationState) panelLocationState = null }
    }
    LaunchedEffect(locationState) { locationState.requestPermission() }

    LaunchedEffect(cameraState) {
      var previous = cameraState.moveReason
      snapshotFlow { cameraState.moveReason }
        .collect { reason ->
          // Follow moves the camera programmatically; a pan is the GESTURE that interrupts it.
          if (previous != CameraMoveReason.GESTURE && reason == CameraMoveReason.GESTURE) {
            follow = false
          }
          previous = reason
        }
    }

    val location = locationState.location
    LaunchedEffect(location) { location?.position?.value?.let { lastFix = it } }

    LocationTrackingEffect(locationState = locationState, enabled = follow) {
      if (previousLocation == null) {
        cameraState.animateTo(
          CameraPosition(target = currentLocation.position.value, zoom = 16.0),
          duration = DemoFlightDuration,
        )
      } else {
        updateCamera(cameraState)
      }
    }

    if (useNativeIndicator) {
      NativeLocationIndicator(location = location, bearing = locationState.mostAccurateBearing())
    } else {
      LocationPuck(
        idPrefix = "user",
        location = location,
        bearing = locationState.mostAccurateBearing(),
        cameraState = cameraState,
        colors = LocationPuckDefaults.colors(),
      )
    }
  }

  @Composable
  override fun Panel() {
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
    if (isNativeLocationIndicatorAvailable) {
      SwitchRow("Native indicator", useNativeIndicator) { useNativeIndicator = it }
    }
    if (demoLocationEngines.size > 1) {
      SegmentedRow(
        label = "Location engine",
        options = demoLocationEngines,
        selected = engine,
        optionLabel = { it.label },
        onSelect = { engine = it },
      )
    }
  }
}

private fun LocationState.statusMessage(): String =
  when (val status = this.status) {
    LocationTrackingStatus.Stopped -> "Location is off"
    LocationTrackingStatus.WaitingForPermission -> {
      val permission = this.permission
      if (permission is LocationPermission.NotGranted && permission.canRequest == false) {
        "Location permission was denied; turn it on in the system settings"
      } else {
        "Waiting for location permission"
      }
    }
    LocationTrackingStatus.Starting -> "Finding your location"
    LocationTrackingStatus.Tracking -> "Tracking your location"
    is LocationTrackingStatus.Unavailable ->
      when (status.reason) {
        LocationUnavailableReason.ServicesDisabled -> "Location services are turned off"
        LocationUnavailableReason.TemporarilyUnavailable -> "Location is temporarily unavailable"
        LocationUnavailableReason.Unsupported -> "Location is not available on this device"
        LocationUnavailableReason.Misconfigured -> "Location is misconfigured on this device"
        LocationUnavailableReason.PermissionDenied -> "Location permission was denied"
        LocationUnavailableReason.UnexpectedFailure -> "Location failed"
      }
  }
