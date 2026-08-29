package org.maplibre.compose.location

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.CLLocationManager
import platform.Foundation.NSError

class IosHeadingProviderTest {
  @Test
  fun unavailableHeadingCompletesWithoutValues() = runTest {
    val headings =
      IosHeadingProvider(
          isHeadingAvailable = { false },
          coroutineContext = EmptyCoroutineContext,
        )
        .updates(HeadingRequest(Duration.ZERO))
        .toList()

    assertTrue(headings.isEmpty())
  }

  @Test
  fun coreLocationFailureTerminatesWithCause() = runTest {
    val channel = Channel<Heading>()
    val delegate = IosHeadingDelegate(channel)
    val error = NSError.errorWithDomain("test.heading", 1, null)

    delegate.locationManager(CLLocationManager(), error)

    val exception = assertIs<IosHeadingException>(channel.receiveCatching().exceptionOrNull())
    assertEquals(error, exception.error)
  }
}
