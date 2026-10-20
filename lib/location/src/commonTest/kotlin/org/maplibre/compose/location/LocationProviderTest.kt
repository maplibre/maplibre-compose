package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

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
    val exception = assertFailsWith<IllegalStateException> { provider.updates().toList() }
    assertSame(cause, exception.cause)
  }
}
