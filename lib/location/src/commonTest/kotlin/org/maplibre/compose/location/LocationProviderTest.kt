package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.location.internal.InternalMaplibreComposeApi
import org.maplibre.compose.location.internal.withBackendAvailability

@OptIn(InternalMaplibreComposeApi::class)
class LocationProviderTest {
  @Test
  fun unsupportedProviderEmitsUnavailableAndCompletes() = runTest {
    assertEquals(
      listOf(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported)),
      UnsupportedLocationProvider.updates(LocationRequest.Standard).toList(),
    )
  }

  @Test
  fun misconfiguredProviderRejectsUpdateCollection() = runTest {
    val cause = IllegalStateException("missing configuration")
    val provider = UnavailableLocationProvider(LocationBackendAvailability.Misconfigured(cause))
    val failure = assertFailsWith<IllegalStateException> { provider.updates().toList() }
    assertSame(cause, failure.cause)
  }

  @Test
  fun unavailableBackendsNeverStartPlatformRequests() = runTest {
    for (availability in
      listOf(
        LocationBackendAvailability.Unsupported,
        LocationBackendAvailability.Misconfigured(),
      )) {
      var started = false
      val updates =
        callbackFlow<LocationEvent> { started = true }.withBackendAvailability(availability)
      if (availability == LocationBackendAvailability.Unsupported) {
        assertEquals(
          listOf(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported)),
          updates.toList(),
        )
      } else {
        assertFailsWith<IllegalStateException> { updates.toList() }
      }
      assertEquals(false, started)
    }
  }

  @Test
  fun availableRequestsAreColdAndCleanUpEachCollection() = runTest {
    var starts = 0
    var stops = 0
    val event = LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable)
    val updates = callbackFlow {
      starts++
      send(event)
      awaitClose { stops++ }
    }
      .withBackendAvailability(LocationBackendAvailability.Available)
    assertEquals(0, starts)
    repeat(2) { assertEquals(event, updates.first()) }
    assertEquals(2, starts)
    assertEquals(2, stops)
  }
}
