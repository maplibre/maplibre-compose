package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.extensions.meters

@OptIn(ExperimentalCoroutinesApi::class)
class LocationProviderTest {
  @Test
  fun cancellingCollectionStopsUpdates() = runTest {
    val provider = FakeLocationProvider()

    val collection = launch { provider.updates(LocationRequest()).collect {} }
    runCurrent()
    assertTrue(provider.active)

    collection.cancel()
    runCurrent()
    assertFalse(provider.active)
  }

  @Test
  fun currentLocationSkipsUnavailableEventsAndStopsAfterFix() = runTest {
    val expected = location()
    val provider =
      FakeLocationProvider(
        events =
          listOf(
            LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable),
            LocationEvent.Fix(expected),
          )
      )

    assertEquals(expected, provider.currentLocation())
    assertFalse(provider.active)
  }

  @Test
  fun currentLocationTimeoutStopsUpdates() = runTest {
    val provider = FakeLocationProvider()

    assertFailsWith<TimeoutCancellationException> {
      provider.currentLocation(timeout = 1.milliseconds)
    }
    assertFalse(provider.active)
  }

  private fun location(): Location =
    Location(
      position = PositionWithAccuracy(Position(13.0, 52.0), 4.meters),
      timestamp = TimeSource.Monotonic.markNow(),
    )
}

private class FakeLocationProvider(private val events: List<LocationEvent> = emptyList()) :
  LocationProvider {
  var active = false

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    active = true
    try {
      events.forEach { emit(it) }
      awaitCancellation()
    } finally {
      active = false
    }
  }
}
