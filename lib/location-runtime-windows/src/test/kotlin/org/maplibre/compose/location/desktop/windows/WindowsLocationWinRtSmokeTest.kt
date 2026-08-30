package org.maplibre.compose.location.desktop.windows

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.location.LocationProviderAvailability

class WindowsLocationWinRtSmokeTest {
  @Test
  fun activatesAppCapabilityAndGeolocatorAndSubscribesWithoutPrompting() {
    if (!isWindows(System.getProperty("os.name"))) return
    val client = SystemWindowsLocationClient()
    assertEquals(LocationProviderAvailability.Available, client.backendAvailability)

    client.checkAccess()
    val permissionObservation = client.observeAccess {}
    val failure = AtomicReference<Throwable?>()
    val session =
      client.createSession(
        WindowsLocationConfiguration(
          desiredAccuracyMeters = 100,
          reportIntervalMilliseconds = 1_000,
        ),
        object : WindowsLocationListener {
          override fun onPosition(measurement: WindowsLocationMeasurement) = Unit

          override fun onStatus(status: WindowsPositionStatus) = Unit

          override fun onFailure(error: Throwable) {
            failure.set(error)
          }
        },
      )

    session.close()
    permissionObservation.close()
    client.close()
    failure.get()?.let { throw AssertionError("WinRT event callback failed", it) }
  }
}
