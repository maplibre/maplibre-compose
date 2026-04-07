package org.maplibre.compose.demoapp.demos

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
      west = 16.34,
      south = 48.19,
      east = 16.40,
      north = 48.23,
    )

  private var demoLocation by mutableStateOf(sampleLocation())
  private var trackingMode by mutableStateOf(UserTrackingMode.FollowWithCourse)

  @Composable
  override fun NativeLocationTracking(state: DemoState, isOpen: Boolean): NativeLocationTracking? {
    if (!isOpen) return null

    val provider = remember { DemoLocationProvider(demoLocation) }
    LaunchedEffect(demoLocation) { provider.update(demoLocation) }

    val nativeTrackingState = rememberNativeLocationTrackingState(trackingMode)
    LaunchedEffect(trackingMode) { nativeTrackingState.trackingMode = trackingMode }

    val locationState = rememberUserLocationState(provider)

    return NativeLocationTracking(
      locationState = locationState,
      state = nativeTrackingState,
      puck = NativeLocationPuck.Default,
    )
  }

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    CardColumn {
      Text("App-provided native location source")
      Text(
        "lat=${demoLocation.position.latitude.format(5)} lon=${demoLocation.position.longitude.format(5)}"
      )
      Text("bearing=${demoLocation.bearing?.format(0) ?: "-"} mode=$trackingMode")

      Button(onClick = { demoLocation = demoLocation.advance(0.001) }) { Text("Advance") }
      Button(onClick = { demoLocation = demoLocation.turn(30.0) }) { Text("Turn +30°") }
      Button(onClick = { demoLocation = demoLocation.turn(-30.0) }) { Text("Turn -30°") }
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
  latitude: Double = 48.20849,
  longitude: Double = 16.37208,
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
  val bearing = bearing ?: 0.0
  val radians = Math.toRadians(bearing)
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

private fun nextTrackingMode(mode: UserTrackingMode): UserTrackingMode =
  when (mode) {
    UserTrackingMode.None -> UserTrackingMode.Follow
    UserTrackingMode.Follow -> UserTrackingMode.FollowWithCourse
    UserTrackingMode.FollowWithCourse -> UserTrackingMode.None
  }

private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
