package org.maplibre.compose.location

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorHeadingFailure
import platform.Foundation.NSError

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

    assertEquals(listOf(HeadingEvent.Unavailable(HeadingUnavailableReason.Unsupported)), events)
  }

  @Test
  fun invalidHeadingsAndHeadingFailuresReportUnreliable() = runTest {
    val channel = Channel<HeadingEvent>(Channel.UNLIMITED)
    val delegate = IosHeadingDelegate(channel)
    val manager = CLLocationManager()

    delegate.locationManager(manager, didUpdateHeading = InvalidHeading())
    delegate.locationManager(
      manager,
      didFailWithError = NSError.errorWithDomain(kCLErrorDomain, kCLErrorHeadingFailure, null),
    )
    channel.close()

    assertEquals(
      listOf(HeadingUnavailableReason.Unreliable, HeadingUnavailableReason.Unreliable),
      channel.toList().map { (it as HeadingEvent.Unavailable).reason },
    )
  }

  private class InvalidHeading : CLHeading() {
    override fun headingAccuracy(): Double = -1.0
  }
}
