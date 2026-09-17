@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.maplibre.spatialk.units.extensions.inMeters
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.Foundation.NSDate

@OptIn(ExperimentalCoroutinesApi::class)
class AppleLocationProviderTest {
  @Test
  fun invalidCoordinatesDoNotReplaceTheLastValidFixOrStopUpdates() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    val manager =
      object : CLLocationManager() {
        override fun startUpdatingLocation() {}

        override fun stopUpdatingLocation() {}
      }
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
      fun send(accuracy: Double) {
        val location =
          CLLocation(
            coordinate = CLLocationCoordinate2DMake(52.0, 13.0),
            altitude = 0.0,
            horizontalAccuracy = accuracy,
            verticalAccuracy = -1.0,
            timestamp = NSDate(),
          )
        checkNotNull(manager.delegate)
          .locationManager(manager, didUpdateLocations = listOf(location))
      }
      send(5.0)
      runCurrent()
      val first = events.single()
      send(-1.0)
      runCurrent()
      assertEquals(listOf(first), events)
      send(0.0)
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
}
