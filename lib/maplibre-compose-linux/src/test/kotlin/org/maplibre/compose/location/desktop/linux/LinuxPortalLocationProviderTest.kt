package org.maplibre.compose.location.desktop.linux

import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.XdgPortalWindow
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.mlnffi.ComposeRenderBackend
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
    val host = FakeComposeMapHost(XdgPortalWindow.X11(0x1234))

    assertEquals("x11:1234", host.withPortalParentWindow { it })
    assertEquals("", null.withPortalParentWindow { it })
  }

  @Test
  fun retainsWaylandExportWhileUsingPortalParent() = runTest {
    val window = FakeWaylandWindow("surface-token")
    val host = FakeComposeMapHost(window)

    val parent = host.withPortalParentWindow {
      assertEquals(listOf("export"), window.events)
      it
    }

    assertEquals("wayland:surface-token", parent)
    assertEquals(listOf("export", "release"), window.events)
  }

  @Test
  fun providerForwardsPortalUpdatesAndClosesItsPortal() = runTest {
    val portal = FakeLinuxLocationPortal()
    val provider = LinuxPortalLocationProvider(portal)

    assertIs<LocationEvent.Fix>(provider.updates(LocationRequest()).first())
    assertEquals(1, portal.updateCollections)

    portal.events = flowOf(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied))
    assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())

    provider.close()
    assertTrue(portal.closed)
  }

  @Test
  fun missingPortalMarksProviderAndRequesterUnsupported() = runTest {
    val portal = FakeLinuxLocationPortal(available = false)
    val provider = LinuxPortalLocationProvider(portal)
    val requester = LinuxPortalLocationPermissionRequester(portal, backgroundScope)

    assertEquals(LocationBackendAvailability.Unsupported, provider.backendAvailability)
    assertEquals(LocationBackendAvailability.Unsupported, requester.backendAvailability)
    requester.requestForegroundPermission()
    runCurrent()
    assertEquals(0, portal.permissionRequests)
  }

  @Test
  fun overlappingPermissionRequestsStartOnePortalRequestAndReuseGrant() = runTest {
    val portal = FakeLinuxLocationPortal()
    val pendingResult = CompletableDeferred<PortalPermissionResult>()
    portal.permissionResult = { pendingResult.await() }
    val requester = LinuxPortalLocationPermissionRequester(portal, backgroundScope)

    requester.requestForegroundPermission()
    requester.requestForegroundPermission()
    runCurrent()
    assertEquals(1, portal.permissionRequests)

    pendingResult.complete(PortalPermissionResult.Granted)
    runCurrent()
    val granted = LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
    assertEquals(granted, requester.status.value)
    requester.requestForegroundPermission()
    assertEquals(1, portal.permissionRequests)

    requester.close()
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

    assertEquals(52.0, event.location.position.value.latitude)
    assertEquals(13.0, event.location.position.value.longitude)
    assertEquals(40.0, event.location.position.value.altitude)
    assertEquals(8.0, event.location.position.accuracy?.inMeters)
    assertEquals(3.0, event.location.speed?.distancePerSecond?.inMeters)
    assertEquals(Bearing.North + 90.degrees, event.location.course?.value)
    assertTrue(event.location.timestamp.elapsedNow() > 1.seconds)
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
}

private class FakeComposeMapHost(override val xdgPortalWindow: XdgPortalWindow?) : ComposeMapHost {
  override val description = "test host"
  override val backend = ComposeRenderBackend.OPENGL

  override fun gpuContext(): ComposeGpuContext? = null

  override fun runOnGpuThread(action: Runnable) = action.run()
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
    closed = true
  }
}
