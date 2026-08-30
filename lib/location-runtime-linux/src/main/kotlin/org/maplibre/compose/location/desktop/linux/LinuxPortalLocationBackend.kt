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
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.DesktopLocationProvider
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationProviderAvailability
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationServicesStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.XdgPortalWindow

/** Linux desktop location backend backed by the XDG Location portal. */
public class LinuxPortalLocationBackend : DesktopLocationBackend {
  override val id: String = "xdg-location-portal"

  override fun isAvailable(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("linux")

  override fun createProvider(window: XdgPortalWindow?): DesktopLocationProvider =
    LinuxPortalLocationProvider(window)
}

// TODO: Add a Linux heading backend when an independent heading API is available.
// iio-sensor-proxy exposes its compass only through GeoClue's location `Heading` field. That field
// can also contain source-provided or derived course, so it cannot reliably provide a device-facing
// heading measurement.

internal suspend fun <T> XdgPortalWindow?.withPortalParentWindow(action: suspend (String) -> T): T =
  when (val window = this) {
    is XdgPortalWindow.X11 -> action("x11:${window.windowId.toString(16)}")
    is XdgPortalWindow.Wayland ->
      window.withXdgForeignHandle { handle -> action(handle?.let { "wayland:$it" }.orEmpty()) }
    null -> action("")
  }

/**
 * A desktop provider that delegates to the
 * [XDG Location portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Location.html).
 *
 * [LocationProvider.permission] and [LocationProvider.requestPermission] delegate to a
 * [LinuxPortalLocationPermissionRequester] that shares the provider's portal.
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
 * Unlike the other desktop backends, this provider ignores [LocationRequest.minimumDistance]. A
 * portal distance threshold suppresses every update, including the first, on a host whose GeoIP
 * position never moves.
 *
 * A missing portal maps [LocationProvider.availability] to
 * [LocationProviderAvailability.Unsupported], and collection emits
 * [LocationUnavailableReason.Unsupported]. A cancelled
 * [`Request.Response`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Request.html#org-freedesktop-portal-request-response)
 * maps to [LocationUnavailableReason.PermissionDenied]. A closed session, a stopped portal service,
 * another non-success response, or a D-Bus transport failure maps to
 * [LocationUnavailableReason.TemporarilyUnavailable]. Malformed location data and other unexpected
 * failures map to [LocationUnavailableReason.UnexpectedFailure].
 */
public class LinuxPortalLocationProvider
internal constructor(
  private val portal: LinuxLocationPortal,
  coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DesktopLocationProvider {
  public constructor(window: XdgPortalWindow? = null) : this(DbusLocationPortal(window))

  private val requester = LinuxPortalLocationPermissionRequester(portal, coroutineScope)
  private val mutableLocationServices = MutableStateFlow(LocationServicesStatus.Unknown)

  override val availability: LocationProviderAvailability =
    if (portal.available) {
      LocationProviderAvailability.Available
    } else {
      LocationProviderAvailability.Unsupported
    }

  override val permission: StateFlow<LocationPermission>
    get() = requester.status

  override val locationServices: StateFlow<LocationServicesStatus> = mutableLocationServices

  override fun requestPermission(): Unit = requester.requestForegroundPermission()

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    if (!portal.available) {
      emit(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
      return@flow
    }
    portal.updates(request).collect { event ->
      when (event) {
        is LocationEvent.Update -> mutableLocationServices.value = LocationServicesStatus.Enabled
        is LocationEvent.Unavailable ->
          if (event.reason == LocationUnavailableReason.ServicesDisabled) {
            mutableLocationServices.value = LocationServicesStatus.Disabled
          }
      }
      emit(event)
    }
  }

  override fun close() {
    requester.close()
    portal.close()
  }
}

/**
 * Observes and requests permission through the XDG Location portal.
 *
 * [LinuxPortalLocationProvider] delegates [LocationProvider.permission] and
 * [LocationProvider.requestPermission] to an instance of this class. Use it directly when a custom
 * provider needs the same portal permission behavior.
 *
 * The portal has no permission-status query. Permission therefore remains
 * [LocationPermission.Unknown] until a successful
 * [`Location.Start`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Location.html#org-freedesktop-portal-location-start)
 * response maps it to [LocationPermission.Granted] with [LocationAccuracyAuthorization.Unknown]. A
 * denied response maps to [LocationPermission.Required] with `canRequest = null`. An unavailable
 * response keeps [LocationPermission.Unknown].
 */
public class LinuxPortalLocationPermissionRequester
internal constructor(
  private val portal: LinuxLocationPortal,
  private val coroutineScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
  public constructor(window: XdgPortalWindow? = null) : this(DbusLocationPortal(window))

  private val mutableStatus = MutableStateFlow<LocationPermission>(LocationPermission.Unknown)

  /** Current foreground location permission. */
  public val status: StateFlow<LocationPermission> = mutableStatus
  private val requestPending = AtomicBoolean()

  /**
   * Starts a foreground permission request and returns immediately. The result is published to
   * [status].
   */
  public fun requestForegroundPermission() {
    if (
      !portal.available ||
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
            PortalPermissionResult.Denied -> LocationPermission.Required(canRequest = null)
            is PortalPermissionResult.Unavailable -> LocationPermission.Unknown
          }
      } finally {
        requestPending.set(false)
      }
    }
  }

  /** Cancels pending requests and closes the portal. */
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
