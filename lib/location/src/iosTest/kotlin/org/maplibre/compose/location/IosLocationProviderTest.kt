package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLErrorNetwork
import platform.Foundation.NSError
import platform.Foundation.NSThread

@OptIn(ExperimentalCoroutinesApi::class)
class IosLocationProviderTest {
  @Test
  fun collectorRecoversAfterPermissionChanges() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    val permissionManager = TestLocationManager()
    val requester = IosLocationPermissionRequester(permissionManager)
    val managers = mutableListOf<TestLocationManager>()
    val provider =
      IosLocationProvider(requester) {
        TestLocationManager().also { managers += it }
      }
    val events = Channel<LocationEvent>(Channel.UNLIMITED)
    val collection = backgroundScope.launch {
      provider.updates(LocationRequest()).collect { events.send(it) }
    }
    try {
      assertEquals(
        LocationUnavailableReason.PermissionDenied,
        (events.receive() as LocationEvent.Unavailable).reason,
      )
      assertTrue(managers.isEmpty())
      permissionManager.status = kCLAuthorizationStatusAuthorizedWhenInUse
      permissionManager.delegate?.locationManagerDidChangeAuthorization(permissionManager)
      runCurrent()
      assertEquals(1, managers.size)
      managers.first().sendLocation()
      assertTrue(events.receive() is LocationEvent.Update)
      permissionManager.status = kCLAuthorizationStatusDenied
      permissionManager.delegate?.locationManagerDidChangeAuthorization(permissionManager)
      assertEquals(
        LocationUnavailableReason.PermissionDenied,
        (events.receive() as LocationEvent.Unavailable).reason,
      )
      assertTrue(managers.all { !it.updating && it.delegate == null })
      permissionManager.status = kCLAuthorizationStatusAuthorizedWhenInUse
      permissionManager.delegate?.locationManagerDidChangeAuthorization(permissionManager)
      runCurrent()
      assertEquals(2, managers.size)
      managers.last().sendLocation()
      assertTrue(events.receive() is LocationEvent.Update)
      collection.cancelAndJoin()
      assertTrue(managers.all { !it.updating && it.delegate == null })
      assertEquals(0, permissionManager.requests)
    } finally {
      collection.cancelAndJoin()
      provider.close()
      Dispatchers.resetMain()
    }
  }

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

private class TestLocationManager : CLLocationManager() {
  var status: CLAuthorizationStatus = kCLAuthorizationStatusDenied
  var updating = false
  var requests = 0

  override fun authorizationStatus(): CLAuthorizationStatus = status

  override fun startUpdatingLocation() {
    updating = true
  }

  override fun stopUpdatingLocation() {
    updating = false
  }

  override fun requestWhenInUseAuthorization() {
    requests++
  }

  fun sendLocation() {
    delegate?.locationManager(this, didUpdateLocations = listOf(CLLocation(52.0, 13.0)))
  }
}
