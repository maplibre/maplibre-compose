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
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.demoapp.demos.DefaultLocationEngine
import org.maplibre.compose.demoapp.demos.DemoLocationEngine
import org.maplibre.compose.demoapp.demos.demoLocationEngines
import org.maplibre.compose.demoapp.design.ButtonRow
import org.maplibre.compose.demoapp.design.DropdownRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.location.BearingUpdate
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.LocationTrackingStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.rememberSystemSettingsLauncher
import org.maplibre.compose.location.updateCamera
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.material3.LocationIndicatorLayer as Material3LocationIndicatorLayer
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position
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
  var followMode by mutableStateOf(DemoFollowMode.Off)
  val mockEngine = MockLocationEngine()
  val engines = demoLocationEngines + mockEngine
  var engine by mutableStateOf(demoLocationEngines.first())
    private set

  var placingMockLocation by mutableStateOf(false)
    private set

  val isMock: Boolean
    get() = engine === mockEngine

  val isTracking: Boolean
    get() = isMock || isFollowing

  fun selectEngine(engine: DemoLocationEngine, mapCenter: Position) {
    if (engine === this.engine) return
    cancelMockPlacement()
    if (engine === mockEngine) mockEngine.sample = mockEngine.sample.copy(position = mapCenter)
    this.engine = engine
  }

  fun beginMockPlacement() {
    if (!isMock) return
    followMode = DemoFollowMode.Off
    placingMockLocation = true
  }

  fun cancelMockPlacement() {
    placingMockLocation = false
  }

  fun placeMockLocation(position: Position?): Boolean {
    if (!isMock || !placingMockLocation || position == null) return false
    mockEngine.sample = mockEngine.sample.copy(position = position)
    placingMockLocation = false
    return true
  }

  fun useMapCenter(position: Position) {
    followMode = DemoFollowMode.Off
    mockEngine.sample = mockEngine.sample.copy(position = position)
    cancelMockPlacement()
  }

  var locationState by mutableStateOf<LocationState?>(null)
    internal set

  var backendId by mutableStateOf<String?>(null)
    internal set

  val isFollowing: Boolean
    get() = followMode != DemoFollowMode.Off

  fun cycleFollow() {
    followMode =
      when (followMode) {
        DemoFollowMode.Off -> DemoFollowMode.Location
        DemoFollowMode.Location -> DemoFollowMode.Heading
        DemoFollowMode.Heading -> DemoFollowMode.Off
      }
    if (isFollowing) locationState?.requestPermission()
  }
}

/**
 * The location puck and camera follow on the shared map. [locationState] is remembered outside the
 * style-tied map content so a style reload keeps the last fix. Permission is requested when the
 * user turns follow on, not when the map first composes.
 */
@MaplibreComposable
@Composable
internal fun DemoLocationMapContent(
  location: DemoLocationUi,
  locationState: LocationState,
  useMaterial3: Boolean,
  flight: CameraAnimation,
) {
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
    if (previousLocation == null) {
      val followBearing =
        if (bearingUpdate == BearingUpdate.IGNORE) null
        else
          currentHeading?.bearing?.let { (it - Bearing.North).inDegrees }
            ?: currentLocation.course?.let { (it - Bearing.North).inDegrees }
      mapState.animateCamera(
        CameraUpdate(
          target = currentLocation.position,
          zoom = 16.0,
          bearing = followBearing,
        ),
        animation = flight,
      )
    } else {
      updateCamera(mapState, updateBearing = bearingUpdate)
    }
  }

  if (useMaterial3) {
    Material3LocationIndicatorLayer(id = "user", locationState = locationState)
  } else {
    LocationIndicatorLayer(id = "user", locationState = locationState)
  }
}

@Composable
internal fun LocationSettingsItems(location: DemoLocationUi, mapCenter: () -> Position) {
  SectionHeader("Location")
  Text(
    text =
      if (location.isTracking) location.locationState?.statusMessage() ?: "Location is off"
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
  if (location.engines.size > 1) {
    DropdownRow(
      label = "Location engine",
      options = location.engines,
      selected = location.engine,
      optionLabel = { it.label },
      onSelect = { location.selectEngine(it, mapCenter()) },
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
