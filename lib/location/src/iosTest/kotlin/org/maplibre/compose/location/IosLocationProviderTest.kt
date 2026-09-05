package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLErrorNetwork
import platform.Foundation.NSError
import platform.Foundation.NSThread

class IosLocationProviderTest {
  @Test
  fun exposesPermissionFromItsRequester() {
    IosLocationPermissionRequester().use { requester ->
      IosLocationProvider().use { provider ->
        assertEquals(requester.status.value, provider.permission.value)
      }
    }
  }

  @Test
  fun closeDetachesPermissionObserverAndRejectsRequests() {
    val manager = CLLocationManager()
    val requester = IosLocationPermissionRequester(manager)
    val provider = IosLocationProvider(requester)
    assertNotNull(manager.delegate)

    provider.close()
    provider.close()

    assertNull(manager.delegate)
    assertFailsWith<IllegalStateException> { provider.requestPermission() }
    assertFailsWith<IllegalStateException> { requester.requestForegroundPermission() }
  }

  @Test
  fun failedOffMainCloseCanStillDisposeOnMain() = runTest {
    val manager = CLLocationManager()
    val requester = IosLocationPermissionRequester(manager)
    try {
      withContext(Dispatchers.Default) {
        assertFailsWith<IllegalStateException> { requester.close() }
      }
      assertNotNull(manager.delegate)
    } finally {
      requester.close()
    }
    assertNull(manager.delegate)
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
