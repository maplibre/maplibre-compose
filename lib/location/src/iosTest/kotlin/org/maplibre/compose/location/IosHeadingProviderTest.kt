package org.maplibre.compose.location

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class IosHeadingProviderTest {
  @Test
  fun missingMagnetometerReportsUnsupportedAndCompletes() = runTest {
    val events =
      IosHeadingProvider(
          isHeadingAvailable = { false },
          coroutineContext = EmptyCoroutineContext,
        )
        .updates()
        .toList()

    assertEquals(listOf(HeadingEvent.Unavailable(LocationUnavailableReason.Unsupported)), events)
  }
}
