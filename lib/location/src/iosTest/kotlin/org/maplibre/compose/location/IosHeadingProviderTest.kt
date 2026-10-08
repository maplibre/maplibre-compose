package org.maplibre.compose.location

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class IosHeadingProviderTest {
  @Test
  fun unavailableHeadingCompletesWithoutValues() = runTest {
    val headings =
      IosHeadingProvider(
          isHeadingAvailable = { false },
          coroutineContext = EmptyCoroutineContext,
        )
        .updates(HeadingRequest { minimumInterval = Duration.ZERO })
        .toList()

    assertTrue(headings.isEmpty())
  }
}
