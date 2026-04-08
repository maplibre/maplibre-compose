package org.maplibre.compose.demoapp.demos

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.PaddingValues
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.NativeLocationPuck
import org.maplibre.compose.location.NativeLocationTracking
import org.maplibre.compose.location.UserTrackingMode
import org.maplibre.compose.location.rememberNativeLocationTrackingState
import org.maplibre.compose.location.rememberUserLocationState
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

object NativeLocationTrackingDemo : Demo {
  override val name = "Native Tracking"

  override val region: BoundingBox =
    BoundingBox(
      west = -122.36,
      south = 47.59,
      east = -122.30,
      north = 47.63,
    )

  private var rawLocation by mutableStateOf(sampleLocation())
  private var trackingMode by mutableStateOf(UserTrackingMode.FollowWithCourse)
  private var isOnRoute by mutableStateOf(true)

  @Composable
  override fun NativeLocationTracking(state: DemoState, isOpen: Boolean): NativeLocationTracking? {
    if (!isOpen) return null

    DisposableEffect(state.cameraState) {
      val previousPadding = state.cameraState.position.padding
      state.cameraState.position =
        state.cameraState.position.copy(padding = PaddingValues(bottom = 220.dp))
      onDispose {
        state.cameraState.position = state.cameraState.position.copy(padding = previousPadding)
      }
    }

    val trackedLocation = rawLocation.toTrackedLocation(isOnRoute)
    val provider = remember { DemoLocationProvider(trackedLocation) }
    LaunchedEffect(trackedLocation) { provider.update(trackedLocation) }

    val nativeTrackingState = rememberNativeLocationTrackingState(trackingMode)
    LaunchedEffect(trackingMode) {
      if (nativeTrackingState.trackingMode != trackingMode) {
        nativeTrackingState.trackingMode = trackingMode
      }
    }
    LaunchedEffect(nativeTrackingState.trackingMode) {
      if (trackingMode != nativeTrackingState.trackingMode) {
        trackingMode = nativeTrackingState.trackingMode
      }
    }

    val locationState = rememberUserLocationState(provider)

    return NativeLocationTracking(
      locationState = locationState,
      state = nativeTrackingState,
      puck = NativeLocationPuck.Default,
    )
  }

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    val trackedLocation = rawLocation.toTrackedLocation(isOnRoute)
    val formattedBearing = trackedLocation.bearing?.let { it.format(0) } ?: "-"

    CardColumn {
      Text("App-provided native location source")
      Text(
        if (isOnRoute) {
          "Preferred source: snapped location drives native puck and follow mode"
        } else {
          "Preferred source: raw location drives native puck and follow mode"
        }
      )
      Text(
        "raw lat=${rawLocation.position.latitude.format(5)} lon=${rawLocation.position.longitude.format(5)}"
      )
      Text(
        "tracked lat=${trackedLocation.position.latitude.format(5)} lon=${trackedLocation.position.longitude.format(5)}"
      )
      Text("bearing=$formattedBearing mode=$trackingMode")

      Button(onClick = { rawLocation = rawLocation.advance(0.001) }) { Text("Advance") }
      Button(onClick = { rawLocation = rawLocation.turn(30.0) }) { Text("Turn +30°") }
      Button(onClick = { rawLocation = rawLocation.turn(-30.0) }) { Text("Turn -30°") }
      Button(onClick = { isOnRoute = !isOnRoute }) {
        Text(if (isOnRoute) "Route state: On route" else "Route state: Off route")
      }
      Button(onClick = { trackingMode = nextTrackingMode(trackingMode) }) {
        Text("Tracking mode: $trackingMode")
      }
    }
  }
}

private class DemoLocationProvider(initialLocation: Location) : LocationProvider {
  private val mutableLocation = MutableStateFlow(initialLocation)

  override val location: StateFlow<Location?> = mutableLocation

  fun update(location: Location) {
    mutableLocation.value = location
  }
}

private fun sampleLocation(
  latitude: Double = 47.6062,
  longitude: Double = -122.3321,
  bearing: Double = 90.0,
): Location =
  Location(
    position = Position(longitude = longitude, latitude = latitude),
    accuracy = 3.0,
    bearing = bearing,
    bearingAccuracy = 1.0,
    speed = 13.0,
    speedAccuracy = 0.5,
    timestamp = TimeSource.Monotonic.markNow(),
  )

private fun Location.advance(stepDegrees: Double): Location {
  val currentBearing = bearing ?: 0.0
  val radians = currentBearing * PI / 180.0
  return copy(
    position =
      Position(
        longitude = position.longitude + stepDegrees * sin(radians),
        latitude = position.latitude + stepDegrees * cos(radians),
      ),
    timestamp = TimeSource.Monotonic.markNow(),
  )
}

private fun Location.turn(deltaDegrees: Double): Location {
  val newBearing = ((bearing ?: 0.0) + deltaDegrees + 360.0) % 360.0
  return copy(bearing = newBearing, timestamp = TimeSource.Monotonic.markNow())
}

private fun Location.toTrackedLocation(isOnRoute: Boolean): Location {
  if (!isOnRoute) return this

  val currentBearing = bearing ?: 0.0
  val radians = (currentBearing + 90.0) * PI / 180.0
  return copy(
    position =
      Position(
        longitude = position.longitude + 0.00025 * sin(radians),
        latitude = position.latitude + 0.00025 * cos(radians),
      ),
      timestamp = TimeSource.Monotonic.markNow(),
    )
}

private fun nextTrackingMode(mode: UserTrackingMode): UserTrackingMode =
  when (mode) {
    UserTrackingMode.None -> UserTrackingMode.Follow
    UserTrackingMode.Follow -> UserTrackingMode.FollowWithCourse
    UserTrackingMode.FollowWithCourse -> UserTrackingMode.None
  }

private fun Double.format(decimals: Int): String {
  val factor = 10.0.pow(decimals)
  return (round(this * factor) / factor).toString()
}
