@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.maplibre.spatialk.units.extensions.inMeters
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLErrorNetwork
import platform.CoreLocation.kCLErrorPromptDeclined
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSThread

@OptIn(ExperimentalCoroutinesApi::class)
class AppleLocationProviderTest {
  @Test
  fun invalidCoordinatesDoNotReplaceTheLastValidFixOrStopUpdates() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    val manager = TestLocationManager()
    val requester =
      AppleLocationPermissionRequester(CLLocationManager()) {
        LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
      }
    val provider =
      AppleLocationProvider(requester, servicesEnabled = { true }, createManager = { manager })
    val events = mutableListOf<LocationEvent>()
    val collection = backgroundScope.launch { provider.updates().collect { events += it } }
    try {
      runCurrent()
      manager.sendLocation(horizontalAccuracy = 5.0)
      runCurrent()
      val first = events.single()
      manager.sendLocation(horizontalAccuracy = -1.0)
      runCurrent()
      assertEquals(listOf(first), events)
      assertTrue(manager.updating)
      manager.sendLocation(horizontalAccuracy = 0.0)
      runCurrent()
      assertEquals(
        listOf(5.0, 0.0),
        events.map { (it as LocationEvent.Update).measurement.horizontalAccuracy?.inMeters },
      )
    } finally {
      collection.cancel()
      runCurrent()
      provider.close()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun collectorRecoversAfterPermissionChanges() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    val permissionManager = TestLocationManager()
    val requester = AppleLocationPermissionRequester(permissionManager)
    val managers = mutableListOf<TestLocationManager>()
    val provider =
      AppleLocationProvider(requester, servicesEnabled = { true }) {
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
  fun deniedAuthorizationReportsGloballyDisabledServices() = runTest {
    val manager = TestLocationManager()
    val provider =
      AppleLocationProvider(
        AppleLocationPermissionRequester(manager),
        servicesEnabled = { false },
        createManager = { error("Disabled services must not start location updates") },
      )
    try {
      val event = assertIs<LocationEvent.Unavailable>(provider.updates().first())
      assertEquals(LocationUnavailableReason.ServicesDisabled, event.reason)
      assertEquals(0, manager.requests)
    } finally {
      provider.close()
    }
  }

  @Test
  fun closeDetachesPermissionObserverAndRejectsRequests() {
    val manager = CLLocationManager()
    val requester = AppleLocationPermissionRequester(manager)
    val provider = AppleLocationProvider(requester)
    assertNotNull(manager.delegate)

    provider.close()
    provider.close()

    assertNull(manager.delegate)
    assertFailsWith<IllegalStateException> { provider.requestPermission() }
    assertFailsWith<IllegalStateException> { requester.requestForegroundPermission() }
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
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      coreLocationError(kCLErrorPromptDeclined).asUnavailableReason { true },
    )
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      coreLocationError(kCLErrorLocationUnknown).asUnavailableReason { true },
    )
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      coreLocationError(kCLErrorNetwork).asUnavailableReason { true },
    )
    assertEquals(
      LocationUnavailableReason.UnexpectedFailure,
      NSError.errorWithDomain("example.error", 1, null).asUnavailableReason { true },
    )
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

  fun sendLocation(horizontalAccuracy: Double = 5.0) {
    val location =
      CLLocation(
        coordinate = CLLocationCoordinate2DMake(52.0, 13.0),
        altitude = 0.0,
        horizontalAccuracy = horizontalAccuracy,
        verticalAccuracy = -1.0,
        timestamp = NSDate(),
      )
    delegate?.locationManager(this, didUpdateLocations = listOf(location))
  }
}
