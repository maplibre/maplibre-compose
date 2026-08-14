package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
}

private class FakeLocationProvider : LocationProvider {
  var active = false

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    active = true
    try {
      awaitCancellation()
    } finally {
      active = false
    }
  }
}
