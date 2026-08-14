package org.maplibre.compose.location.desktop.linux

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.XdgPortalWindow
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.DesktopLocationPermissionRequester
import org.maplibre.compose.location.DesktopLocationProvider
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationPermissionRequester
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason

/** Linux desktop location backend backed by the XDG Location portal. */
public class LinuxPortalLocationBackend : DesktopLocationBackend {
  override val id: String = "xdg-location-portal"

  override fun isAvailable(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("linux")

  override fun createProvider(host: ComposeMapHost?): DesktopLocationProvider =
    LinuxPortalLocationProvider(host)

  override fun createPermissionRequester(
    host: ComposeMapHost?
  ): DesktopLocationPermissionRequester = LinuxPortalLocationPermissionRequester(host)
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
 * A desktop provider that delegates to the
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
 * A missing portal maps [LocationProvider.backendAvailability] to
 * [LocationBackendAvailability.Unsupported], and collection emits
 * [LocationUnavailableReason.Unsupported]. A cancelled
 * [`Request.Response`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Request.html#org-freedesktop-portal-request-response)
 * maps to [LocationUnavailableReason.PermissionDenied]. A closed session, a stopped portal service,
 * another non-success response, or a D-Bus transport failure maps to
 * [LocationUnavailableReason.TemporarilyUnavailable]. Malformed location data and other unexpected
 * failures map to [LocationUnavailableReason.UnexpectedFailure].
 */
public class LinuxPortalLocationProvider
internal constructor(private val portal: LinuxLocationPortal) : DesktopLocationProvider {
  public constructor(host: ComposeMapHost? = null) : this(DbusLocationPortal(host))

  override val backendAvailability: LocationBackendAvailability =
    if (portal.available) {
      LocationBackendAvailability.Available
    } else {
      LocationBackendAvailability.Unsupported
    }

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    if (!portal.available) {
      emit(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
      return@flow
    }
    portal.updates(request).collect { emit(it) }
  }

  override fun close() {
    portal.close()
  }
}

/**
 * Observes and requests permission through the XDG Location portal.
 *
 * The portal has no permission-status query. Permission therefore remains
 * [LocationPermission.NotGranted] with `canRequest = null` until a successful
 * [`Location.Start`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Location.html#org-freedesktop-portal-location-start)
 * response maps it to [LocationPermission.Granted] with [LocationAccuracyAuthorization.Unknown].
 * Denied and unavailable responses remain `NotGranted` with `canRequest = null`. A missing portal
 * maps [LocationPermissionRequester.backendAvailability] to
 * [LocationBackendAvailability.Unsupported].
 */
public class LinuxPortalLocationPermissionRequester
internal constructor(
  private val portal: LinuxLocationPortal,
  private val coroutineScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DesktopLocationPermissionRequester {
  public constructor(host: ComposeMapHost? = null) : this(DbusLocationPortal(host))

  override val backendAvailability: LocationBackendAvailability =
    if (portal.available) {
      LocationBackendAvailability.Available
    } else {
      LocationBackendAvailability.Unsupported
    }
  private val mutableStatus =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))
  override val status: StateFlow<LocationPermission> = mutableStatus
  private val requestPending = AtomicBoolean()

  override fun requestForegroundPermission() {
    if (
      backendAvailability != LocationBackendAvailability.Available ||
        status.value is LocationPermission.Granted ||
        !requestPending.compareAndSet(false, true)
    ) {
      return
    }
    coroutineScope.launch {
      try {
        mutableStatus.value =
          when (portal.requestPermission()) {
            PortalPermissionResult.Granted ->
              LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
            PortalPermissionResult.Denied -> LocationPermission.NotGranted(canRequest = null)
            is PortalPermissionResult.Unavailable ->
              LocationPermission.NotGranted(canRequest = null)
          }
      } finally {
        requestPending.set(false)
      }
    }
  }

  override fun close() {
    coroutineScope.cancel()
    portal.close()
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
