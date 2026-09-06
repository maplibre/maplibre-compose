package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.maplibre.compose.location.BearingUpdate
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.LocationTrackingStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.rememberSystemSettingsLauncher
import org.maplibre.compose.location.updateCamera
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.material3.LocationPuckDefaults
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees

/** Camera follow on the location button: off, lock to the puck, or lock bearing as well. */
internal enum class DemoFollowMode {
  Off,
  Location,
  Heading,
}

/** Follow mode, engine choice, and the active [LocationState] for the shared map. */
@Stable
internal class DemoLocationUi {
  private var followModeState by mutableStateOf(DemoFollowMode.Off)
  var followMode: DemoFollowMode
    get() = followModeState
    set(value) {
      followModeState = value
      if (value == DemoFollowMode.Off) lockedFollowCamera = false
    }

  var engine by mutableStateOf(demoLocationEngines.first())
  var locationState by mutableStateOf<LocationState?>(null)
    internal set

  var backendId by mutableStateOf<String?>(null)
    internal set

  /**
   * True after the first follow lock this session, so a remade effect does not zoom to 16 again.
   */
  internal var lockedFollowCamera by mutableStateOf(false)

  val isFollowing: Boolean
    get() = followMode != DemoFollowMode.Off

  fun onFollowClick() {
    if (followVisual() == DemoFollowVisual.Disabled) {
      tryFollow()
    } else {
      cycleFollow()
    }
  }

  private fun cycleFollow() {
    followMode =
      when (followMode) {
        DemoFollowMode.Off -> DemoFollowMode.Location
        DemoFollowMode.Location -> DemoFollowMode.Heading
        DemoFollowMode.Heading -> DemoFollowMode.Off
      }
    if (isFollowing) locationState?.requestPermission()
  }

  /**
   * Starts follow if needed and restarts tracking, matching the disabled button's "Try following".
   */
  private fun tryFollow() {
    if (followMode == DemoFollowMode.Off) followMode = DemoFollowMode.Location
    locationState?.requestPermission()
    locationState?.retry()
  }
}

/**
 * The location puck and camera follow on the shared map. [locationState] is remembered outside the
 * style-tied map content so a style reload keeps the last fix. Permission is requested when the
 * user turns follow on, not when the map first composes.
 */
@MaplibreComposable
@Composable
internal fun DemoLocationMapContent(location: DemoLocationUi, locationState: LocationState) {
  val mapState = checkNotNull(LocalMapState.current)

  LaunchedEffect(mapState) {
    var previous = mapState.cameraMoveReason
    snapshotFlow { mapState.cameraMoveReason }
      .collect { reason ->
        if (previous != CameraMoveReason.GESTURE && reason == CameraMoveReason.GESTURE) {
          location.followMode = DemoFollowMode.Off
        }
        previous = reason
      }
  }

  LocationTrackingEffect(
    locationState = locationState,
    enabled = location.isFollowing,
    trackBearing = location.followMode == DemoFollowMode.Heading,
  ) {
    val bearingUpdate =
      when (location.followMode) {
        DemoFollowMode.Off -> return@LocationTrackingEffect
        DemoFollowMode.Location -> BearingUpdate.IGNORE
        DemoFollowMode.Heading -> BearingUpdate.TRACK_AUTOMATIC
      }
    if (previousLocation == null && !location.lockedFollowCamera) {
      val followBearing =
        if (bearingUpdate == BearingUpdate.IGNORE) mapState.cameraPosition.bearing
        else
          currentHeading?.bearing?.let { (it - Bearing.North).inDegrees }
            ?: currentLocation.course?.let { (it - Bearing.North).inDegrees }
            ?: mapState.cameraPosition.bearing
      mapState.animateCameraPosition(
        CameraPosition(
          target = currentLocation.position,
          zoom = 16.0,
          bearing = followBearing,
        ),
        duration = DemoFlightDuration,
      )
      location.lockedFollowCamera = true
    } else {
      updateCamera(mapState, updateBearing = bearingUpdate)
      location.lockedFollowCamera = true
    }
  }

  LocationPuck(
    idPrefix = "user",
    locationState = locationState,
    colors = LocationPuckDefaults.colors(),
  )
}

@Composable
fun LocationSettingsItems(state: DemoAppState) {
  LocationSettingsItems(state.location)
}

@Composable
internal fun LocationSettingsItems(location: DemoLocationUi) {
  SectionHeader("Location")
  Text(
    text =
      if (location.isFollowing) location.locationState?.statusMessage() ?: "Location is off"
      else "Location is off",
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

/** Which glyph the overlay location button should show. */
internal enum class DemoFollowVisual {
  Idle,
  Searching,
  Following,
  Heading,
  Disabled,
}

internal fun DemoLocationUi.followVisual(): DemoFollowVisual {
  val state = locationState
  val permission = state?.permission
  val deniedPermanently =
    permission is LocationPermission.NotGranted && permission.canRequest == false
  val blocked =
    deniedPermanently ||
      state?.availability == LocationBackendAvailability.Unsupported ||
      state?.availability is LocationBackendAvailability.Misconfigured ||
      (state?.status as? LocationTrackingStatus.Unavailable)?.reason ==
        LocationUnavailableReason.ServicesDisabled
  if (blocked) return DemoFollowVisual.Disabled
  if (!isFollowing) return DemoFollowVisual.Idle
  if (state?.status != LocationTrackingStatus.Tracking) return DemoFollowVisual.Searching
  return if (followMode == DemoFollowMode.Heading) DemoFollowVisual.Heading
  else DemoFollowVisual.Following
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
