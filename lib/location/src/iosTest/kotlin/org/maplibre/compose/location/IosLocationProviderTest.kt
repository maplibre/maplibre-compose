package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLErrorNetwork
import platform.Foundation.NSError
import platform.Foundation.NSThread

class IosLocationProviderTest {
  @Test
  fun exposesPermissionFromItsRequester() {
    assertEquals(
      IosLocationPermissionRequester().status.value,
      IosLocationProvider().permission.value,
    )
  }

  @Test
  fun readsLocationServicesStatusOffMainThread() = runTest {
    assertTrue(NSThread.isMainThread)
    assertFalse(readLocationServicesEnabled { NSThread.isMainThread })
  }

  @Test
  fun mapsCoreLocationErrorsByRecoverability() = runTest {
    assertEquals(
      LocationUnavailableReason.ServicesDisabled,
      coreLocationError(kCLErrorDenied).asUnavailableReason { false },
    )
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      coreLocationError(kCLErrorDenied).asUnavailableReason { true },
    )
    var locationServicesQueried = false
    val locationServicesEnabled = {
      locationServicesQueried = true
      true
    }
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      coreLocationError(kCLErrorLocationUnknown).asUnavailableReason(locationServicesEnabled),
    )
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      coreLocationError(kCLErrorNetwork).asUnavailableReason(locationServicesEnabled),
    )
    assertEquals(
      LocationUnavailableReason.UnexpectedFailure,
      NSError.errorWithDomain("example.error", 1, null)
        .asUnavailableReason(locationServicesEnabled),
    )
    assertFalse(locationServicesQueried)
  }

  private fun coreLocationError(code: Long): NSError =
    NSError.errorWithDomain(kCLErrorDomain, code, null)
}
