package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    val provider =
      UnavailableLocationProvider(
        LocationBackendAvailability.Misconfigured(IllegalStateException("missing configuration")),
      )
    assertFailsWith<IllegalStateException> { provider.updates().toList() }
  }
}
