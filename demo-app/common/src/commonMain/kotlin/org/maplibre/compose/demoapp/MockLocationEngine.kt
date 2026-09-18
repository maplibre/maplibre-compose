package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.maplibre.compose.demoapp.demos.DemoLocationEngine
import org.maplibre.compose.location.HeadingMeasurement
import org.maplibre.compose.location.HeadingProvider
import org.maplibre.compose.location.HeadingReference
import org.maplibre.compose.location.HeadingRequest
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationMeasurement
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

internal data class MockLocationSample(
  val position: Position = Position(longitude = -74.006, latitude = 40.7128),
  val bearing: Float = 0f,
  val positionAccuracy: Float = 20f,
  val bearingAccuracy: Float = 15f,
  val headingAvailable: Boolean = true,
  val positionAccuracyKnown: Boolean = true,
  val bearingAccuracyKnown: Boolean = true,
)

/** Editable measurements shared by the mock location and heading providers. */
@Stable
internal class MockLocationEngine : DemoLocationEngine {
  override val label = "Mock"
  var sample by mutableStateOf(MockLocationSample())

  @Composable
  override fun rememberLocationProvider(): LocationProvider =
    // LocationState retains headings. A fresh provider clears that history when availability
    // changes.
    remember(this, sample.headingAvailable) { createLocationProvider() }

  fun createLocationProvider(): LocationProvider =
    object : LocationProvider {
      override val backendId = "demo-mock"

      override fun updates(request: LocationRequest): Flow<LocationEvent> = snapshotFlow {
        sample
      }
        .map {
          LocationEvent.Update(
            measurement =
              LocationMeasurement(
                position = it.position,
                horizontalAccuracy =
                  it.positionAccuracy.takeIf { _ -> it.positionAccuracyKnown }?.toDouble()?.meters,
                measuredAt = Clock.System.now(),
              ),
            measurementMark = TimeSource.Monotonic.markNow(),
          )
        }
    }

  @Composable
  override fun rememberHeadingProvider(): HeadingProvider =
    remember(this, sample.headingAvailable) { createHeadingProvider() }

  fun createHeadingProvider(): HeadingProvider =
    object : HeadingProvider {
      override fun updates(request: HeadingRequest): Flow<HeadingMeasurement> =
        if (!sample.headingAvailable) emptyFlow()
        else
          snapshotFlow { sample }
            .map {
              HeadingMeasurement(
                bearing = Bearing.North + it.bearing.toDouble().degrees,
                reference = HeadingReference.TrueNorth,
                accuracy =
                  it.bearingAccuracy.takeIf { _ -> it.bearingAccuracyKnown }?.toDouble()?.degrees,
                measuredAt = Clock.System.now(),
              )
            }
    }
}
