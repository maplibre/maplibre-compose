package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

@OptIn(ExperimentalCoroutinesApi::class)
class HeadingProviderTest {
  @Test
  fun throttleHoldsBackOnlyUpdatesAndKeepsTheLastOne() = runTest {
    val unavailable = HeadingEvent.Unavailable(HeadingUnavailableReason.Unreliable)
    val events = flow {
      emit(update(1.0))
      delay(1.seconds)
      emit(unavailable)
      delay(1.seconds)
      emit(update(2.0))
      emit(update(3.0))
    }
      .throttleUpdates(10.seconds)
      .map { currentTime to it }
      .toList()

    assertEquals(listOf(0L to update(1.0), 1_000L to unavailable, 10_000L to update(3.0)), events)
    assertEquals(10_000L, currentTime)
  }

  private fun update(degrees: Double) =
    HeadingEvent.Update(
      HeadingMeasurement(
        bearing = Bearing.North + degrees.degrees,
        reference = HeadingReference.MagneticNorth,
        measuredAt = Instant.fromEpochMilliseconds(0),
      ),
    )
}
