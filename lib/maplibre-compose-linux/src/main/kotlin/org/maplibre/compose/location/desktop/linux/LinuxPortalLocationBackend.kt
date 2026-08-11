package org.maplibre.compose.location.desktop.linux

import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.XdgPortalWindow
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.DesktopLocationProvider
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationPermissionController
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason

/** Linux desktop location backend backed by the XDG Location portal. */
public class LinuxPortalLocationBackend : DesktopLocationBackend {
  override val id: String = "xdg-location-portal"

  override fun isAvailable(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("linux")

  override fun createProvider(host: ComposeMapHost?): DesktopLocationProvider =
    LinuxPortalLocationProvider(DbusLocationPortal(host))
}

// TODO: Add a Linux orientation backend when an independent heading API is available.
// iio-sensor-proxy restricts its compass interface to GeoClue. GeoClue folds that reading into the
// location Heading field, which can instead contain source-provided or derived course, so it cannot
// be mapped reliably to Orientation.

internal suspend fun <T> ComposeMapHost?.withPortalParentWindow(action: suspend (String) -> T): T =
  when (val window = this?.xdgPortalWindow) {
    is XdgPortalWindow.X11 -> action("x11:${window.windowId.toString(16)}")
    is XdgPortalWindow.Wayland ->
      window.withXdgForeignHandle { handle -> action(handle?.let { "wayland:$it" }.orEmpty()) }
    null -> action("")
  }

/**
 * A cold-session desktop provider that delegates to the
 * [XDG Location portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Location.html).
 *
 * [LocationAccuracy.BestForNavigation] and [LocationAccuracy.High] map to
 * [`EXACT`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Location.html#org-freedesktop-portal-location-createsession),
 * [LocationAccuracy.Balanced] maps to
 * [`STREET`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Location.html#org-freedesktop-portal-location-createsession),
 * [LocationAccuracy.Low] maps to
 * [`CITY`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Location.html#org-freedesktop-portal-location-createsession),
 * and [LocationAccuracy.Lowest] maps to
 * [`COUNTRY`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Location.html#org-freedesktop-portal-location-createsession).
 *
 * The portal exposes the result of a request but no permission-status query. Permission therefore
 * remains [LocationPermission.NotGranted] with `canRequest = null` until a request succeeds.
 *
 * A missing portal maps to [LocationUnavailableReason.Unsupported]. A cancelled
 * [`Request.Response`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Request.html#org-freedesktop-portal-request-response)
 * maps to [LocationUnavailableReason.PermissionDenied]. A closed session, a stopped portal service,
 * another non-success response, or a D-Bus transport failure maps to
 * [LocationUnavailableReason.TemporarilyUnavailable]. Malformed location data and other unexpected
 * failures map to [LocationUnavailableReason.UnexpectedFailure].
 */
public class LinuxPortalLocationProvider
internal constructor(private val portal: LinuxLocationPortal) : DesktopLocationProvider {
  private val permissionController = LinuxPortalPermissionController(portal)

  override val permission: LocationPermissionController = permissionController
  override val isSupported: Boolean = portal.available

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    if (!portal.available) {
      emit(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
      return@flow
    }
    if (permission.status.value !is LocationPermission.Granted) {
      emit(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied))
      return@flow
    }

    emitAll(
      portal.updates(request).onEach { event ->
        if (
          event is LocationEvent.Unavailable &&
            event.reason == LocationUnavailableReason.PermissionDenied
        ) {
          permissionController.acceptDenied()
        }
      }
    )
  }

  override fun close() {
    portal.close()
  }
}

private class LinuxPortalPermissionController(private val portal: LinuxLocationPortal) :
  LocationPermissionController {
  private val mutableStatus =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))
  override val status: StateFlow<LocationPermission> = mutableStatus
  private val requestMutex = Mutex()
  private var pendingRequest: CompletableDeferred<LocationPermission>? = null

  override suspend fun requestForegroundPermission(): LocationPermission {
    var startsRequest = false
    val request = requestMutex.withLock {
      val current = mutableStatus.value
      if (current is LocationPermission.Granted) return current
      pendingRequest
        ?: CompletableDeferred<LocationPermission>().also {
          pendingRequest = it
          startsRequest = true
        }
    }
    if (!startsRequest) return request.await()

    try {
      val result =
        when (portal.requestPermission()) {
          PortalPermissionResult.Granted ->
            LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
          PortalPermissionResult.Denied -> LocationPermission.NotGranted(canRequest = null)
          is PortalPermissionResult.Unavailable -> LocationPermission.NotGranted(canRequest = null)
        }
      mutableStatus.value = result
      request.complete(result)
      return result
    } catch (error: Throwable) {
      request.completeExceptionally(error)
      throw error
    } finally {
      requestMutex.withLock {
        if (pendingRequest === request) pendingRequest = null
      }
    }
  }

  fun acceptDenied() {
    mutableStatus.value = LocationPermission.NotGranted(canRequest = null)
  }
}

internal sealed interface PortalPermissionResult {
  data object Granted : PortalPermissionResult

  data object Denied : PortalPermissionResult

  data class Unavailable(
    val reason: LocationUnavailableReason,
    val cause: Throwable? = null,
  ) : PortalPermissionResult
}

internal interface LinuxLocationPortal : AutoCloseable {
  val available: Boolean

  suspend fun requestPermission(): PortalPermissionResult

  fun updates(request: LocationRequest): Flow<LocationEvent>
}
