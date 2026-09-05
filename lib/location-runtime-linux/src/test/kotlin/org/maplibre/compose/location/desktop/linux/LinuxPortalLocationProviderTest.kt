package org.maplibre.compose.location.desktop.linux

import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.XdgPortalWindow
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inMeters

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxPortalLocationProviderTest {
  @Test
  fun serviceLoaderFindsLinuxBackend() {
    assertTrue(
      ServiceLoader.load(DesktopLocationBackend::class.java).any {
        it is LinuxPortalLocationBackend
      }
    )
  }

  @Test
  fun formatsX11WindowAsPortalParent() = runTest {
    val window = XdgPortalWindow.X11(0x1234)

    assertEquals("x11:1234", window.withPortalParentWindow { it })
    assertEquals("", null.withPortalParentWindow { it })
  }

  @Test
  fun retainsWaylandExportWhileUsingPortalParent() = runTest {
    val window = FakeWaylandWindow("surface-token")

    val parent = window.withPortalParentWindow {
      assertEquals(listOf("export"), window.events)
      it
    }

    assertEquals("wayland:surface-token", parent)
    assertEquals(listOf("export", "release"), window.events)
  }

  @Test
  fun correlatesResponsesWithCurrentPortalRequestPath() {
    val responsePath = PortalResponsePath(":1.42", "maplibre_token")

    assertEquals(
      "/org/freedesktop/portal/desktop/request/1_42/maplibre_token",
      responsePath.current,
    )
    assertTrue(responsePath.accepts(responsePath.current))
    assertTrue(!responsePath.accepts("/org/freedesktop/portal/desktop/request/1_99/other"))

    responsePath.update("/legacy/request/path")
    assertTrue(responsePath.accepts("/legacy/request/path"))
    assertTrue(!responsePath.accepts("/org/freedesktop/portal/desktop/request/1_42/maplibre_token"))
  }

  @Test
  fun providerForwardsPortalUpdatesAndClosesItsPortal() = runTest {
    val portal = FakeLinuxLocationPortal()
    val provider = LinuxPortalLocationProvider(portal)

    assertIs<LocationEvent.Update>(provider.updates(LocationRequest()).first())
    assertEquals(1, portal.updateCollections)

    portal.events = flowOf(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied))
    assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())

    provider.close()
    assertTrue(portal.closed)
  }

  @Test
  fun missingPortalMarksProviderUnsupported() = runTest {
    val portal = FakeLinuxLocationPortal(available = false)
    val provider = LinuxPortalLocationProvider(portal, backgroundScope)

    assertEquals(LocationBackendAvailability.Unsupported, provider.backendAvailability)
    assertFailsWith<IllegalStateException> { provider.updates(LocationRequest()).first() }
    provider.requestPermission()
    runCurrent()
    assertEquals(0, portal.permissionRequests)
  }

  @Test
  fun overlappingPermissionRequestsStartOnePortalRequestAndReuseGrant() = runTest {
    val portal = FakeLinuxLocationPortal()
    val pendingResult = CompletableDeferred<PortalPermissionResult>()
    portal.permissionResult = { pendingResult.await() }
    val provider = LinuxPortalLocationProvider(portal, backgroundScope)

    provider.requestPermission()
    provider.requestPermission()
    runCurrent()
    assertEquals(1, portal.permissionRequests)

    pendingResult.complete(PortalPermissionResult.Granted)
    runCurrent()
    val granted = LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
    assertEquals(granted, provider.permission.value)
    provider.requestPermission()
    assertEquals(1, portal.permissionRequests)

    provider.close()
    assertTrue(portal.closed)
  }

  @Test
  fun convertsPortalLocationDictionary() {
    val event =
      mapOf(
          "Latitude" to Variant(52.0),
          "Longitude" to Variant(13.0),
          "Altitude" to Variant(40.0),
          "Accuracy" to Variant(8.0),
          "Speed" to Variant(3.0),
          "Heading" to Variant(90.0),
          "Timestamp" to Variant(arrayOf(UInt64(1_700_000_000L), UInt64(123_000L)), "(tt)"),
        )
        .toLocationEvent()

    assertEquals(52.0, event.measurement.position.latitude)
    assertEquals(13.0, event.measurement.position.longitude)
    assertEquals(40.0, event.measurement.position.altitude)
    assertEquals(8.0, event.measurement.horizontalAccuracy?.inMeters)
    assertEquals(3.0, event.measurement.distancePerSecond?.inMeters)
    assertEquals(Bearing.North + 90.degrees, event.measurement.course)
    assertTrue(event.measurementMark.elapsedNow() < 1.seconds)
  }

  @Test
  fun convertsUnknownPortalMeasurementsToNull() {
    val event =
      mapOf(
          "Latitude" to Variant(52.0),
          "Longitude" to Variant(13.0),
          "Altitude" to Variant(-Double.MAX_VALUE),
          "Speed" to Variant(-1.0),
          "Heading" to Variant(-1.0),
        )
        .toLocationEvent()

    assertEquals(null, event.measurement.position.altitude)
    assertEquals(null, event.measurement.distancePerSecond)
    assertEquals(null, event.measurement.course)
  }

  @Test
  fun realPortalSessionCanOpenAndClose() = runTest {
    if (System.getenv("MAPLIBRE_TEST_LINUX_LOCATION_PORTAL") != "true") return@runTest

    val portal = DbusLocationPortal()
    assertTrue(portal.available)
    val result = withTimeout(30.seconds) { portal.requestPermission() }
    assertNotEquals(PortalPermissionResult.Unavailable::class, result::class)
    portal.close()
  }

  @Test
  fun cancellingCollectorWaitsForPortalSessionCleanup() = runTest {
    val portal = FakeLinuxLocationPortal()
    var cleanedUp = false
    portal.events = flow {
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) { delay(1) }
        cleanedUp = true
      }
    }
    val provider = LinuxPortalLocationProvider(portal, backgroundScope)
    val collection = launch { provider.updates().collect {} }
    runCurrent()

    collection.cancelAndJoin()

    assertTrue(cleanedUp)
    assertEquals(0, portal.closeCount)
    provider.close()
    assertEquals(1, portal.closeCount)
  }

  @Test
  fun providerCloseStopsUpdatesAndPermissionBeforeClosingPortal() = runTest {
    val events = mutableListOf<String>()
    val portal = FakeLinuxLocationPortal()
    portal.events = flow {
      try {
        awaitCancellation()
      } finally {
        events += "updates stopped"
      }
    }
    portal.permissionResult = {
      try {
        awaitCancellation()
      } finally {
        events += "permission stopped"
      }
    }
    portal.onClose = { events += "portal closed" }
    val provider = LinuxPortalLocationProvider(portal, backgroundScope)
    val collection = launch { provider.updates().collect {} }
    provider.requestPermission()
    runCurrent()
    assertEquals(1, portal.updateCollections)
    assertEquals(1, portal.permissionRequests)

    provider.close()
    provider.close()
    collection.join()
    runCurrent()

    assertEquals(1, portal.closeCount)
    assertEquals("portal closed", events.last())
    assertEquals(setOf("updates stopped", "permission stopped", "portal closed"), events.toSet())
    assertTrue(backgroundScope.isActive)
    assertFailsWith<IllegalStateException> { provider.updates().first() }
    assertFailsWith<IllegalStateException> { provider.requestPermission() }
  }

  @Test
  fun closeDoesNotCancelCallerScope() {
    val callerScope = CoroutineScope(SupervisorJob())
    val requester = LinuxPortalLocationPermissionRequester(FakeLinuxLocationPortal(), callerScope)
    requester.close()
    requester.close()
    assertFailsWith<IllegalStateException> { requester.requestForegroundPermission() }
    assertTrue(callerScope.isActive)
  }
}

private class FakeWaylandWindow(private val handle: String?) : XdgPortalWindow.Wayland {
  val events = mutableListOf<String>()

  override suspend fun <T> withXdgForeignHandle(action: suspend (String?) -> T): T {
    events += "export"
    return try {
      action(handle)
    } finally {
      events += "release"
    }
  }
}

private class FakeLinuxLocationPortal(override val available: Boolean = true) :
  LinuxLocationPortal {
  var closeCount = 0
  var onClose: () -> Unit = {}
  var closed = false
  var updateCollections = 0
  var permissionRequests = 0
  var permissionResult: suspend () -> PortalPermissionResult = { PortalPermissionResult.Granted }
  var events: Flow<LocationEvent> =
    flowOf(
      mapOf(
          "Latitude" to Variant(52.0),
          "Longitude" to Variant(13.0),
          "Accuracy" to Variant(8.0),
        )
        .toLocationEvent()
    )

  override suspend fun requestPermission(): PortalPermissionResult {
    permissionRequests += 1
    return permissionResult()
  }

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    updateCollections += 1
    emitAll(events)
  }

  override fun close() {
    closeCount += 1
    onClose()
    closed = true
  }
}
