package org.maplibre.compose.location.desktop.macos

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.DesktopLocationProvider
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.XdgPortalWindow
import org.maplibre.spatialk.units.extensions.inMeters

/** macOS desktop location backend backed by Core Location. */
public class MacosLocationBackend : DesktopLocationBackend {
  override val id: String = "core-location"

  override fun isAvailable(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("mac")

  override fun createProvider(window: XdgPortalWindow?): DesktopLocationProvider =
    MacosLocationProvider()
}

/**
 * Foreground location from macOS Core Location.
 *
 * The application's Info.plist must declare `NSLocationWhenInUseUsageDescription`. Applies the
 * requested accuracy and minimum distance. [LocationRequest.minimumInterval] is ignored. See
 * [MacosLocationPermissionRequester] for permission behavior.
 *
 * Disabled location services report [LocationUnavailableReason.ServicesDisabled]. Denied or
 * declined permission reports [LocationUnavailableReason.PermissionDenied]. Network failures and
 * unknown locations report [LocationUnavailableReason.TemporarilyUnavailable]. Other failures
 * report [LocationUnavailableReason.UnexpectedFailure].
 */
public class MacosLocationProvider
internal constructor(
  private val client: CoreLocationClient,
  private val dispatcher: CoroutineContext = Dispatchers.Main,
  private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) : DesktopLocationProvider {
  public constructor() : this(SystemCoreLocationClient())

  private val requester = MacosLocationPermissionRequester(client)

  override val backendAvailability: LocationBackendAvailability = client.backendAvailability

  override val permission: StateFlow<LocationPermission>
    get() = requester.status

  override fun requestPermission(): Unit = requester.requestForegroundPermission()

  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    check(backendAvailability == LocationBackendAvailability.Available) {
      "Location updates require an available backend: $backendAvailability"
    }
    val locationServicesEnabled = withContext(ioDispatcher) { client.locationServicesEnabled }
    if (!locationServicesEnabled) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.ServicesDisabled))
      close()
      return@callbackFlow
    }

    val manager =
      try {
        client.createManager()
      } catch (error: Throwable) {
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
        close()
        return@callbackFlow
      }

    try {
      val delegate = UpdateDelegate(this, channel, client, ioDispatcher)
      manager.setDelegate(delegate)
      manager.desiredAccuracy = request.accuracy.toDesiredAccuracy()
      manager.distanceFilter = request.minimumDistance.inMeters
      manager.location?.let(delegate::sendLocation)
      manager.startUpdatingLocation()
    } catch (error: Throwable) {
      manager.close()
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
      close()
      return@callbackFlow
    }

    awaitClose { manager.close() }
  }
    .flowOn(dispatcher)

  override fun close() {
    requester.close()
    client.close()
  }

  private class UpdateDelegate(
    private val scope: CoroutineScope,
    private val channel: SendChannel<LocationEvent>,
    private val client: CoreLocationClient,
    private val ioDispatcher: CoroutineContext,
  ) : CoreLocationDelegate {
    override fun didUpdateLocations(locations: List<CoreLocationMeasurement>) {
      locations.forEach(::sendLocation)
    }

    override fun didFailWithError(error: CoreLocationError) {
      if (error.domain == CL_ERROR_DOMAIN && error.code == CL_ERROR_DENIED) {
        scope.launch(ioDispatcher) {
          channel.trySend(
            LocationEvent.Unavailable(error.asUnavailableReason(client.locationServicesEnabled))
          )
        }
        return
      }
      channel.trySend(LocationEvent.Unavailable(error.asUnavailableReason(true)))
    }

    override fun didChangeAuthorization() = Unit

    fun sendLocation(location: CoreLocationMeasurement) {
      if (location.horizontalAccuracy < 0.0) return
      channel.trySend(
        LocationEvent.Update(
          location.asMapLibreLocationMeasurement(),
          TimeSource.Monotonic.markNow() - location.ageAtReceipt(),
        )
      )
    }
  }
}

/**
 * Foreground Core Location permission holder.
 *
 * [MacosLocationProvider] delegates [LocationProvider.permission] and
 * [LocationProvider.requestPermission] to an instance of this class. Use it directly when a custom
 * provider needs the same Core Location permission behavior.
 *
 * [`CLAuthorizationStatus`](https://developer.apple.com/documentation/corelocation/clauthorizationstatus)
 * maps an authorized status to [LocationPermission.Granted], `notDetermined` to
 * [LocationPermission.NotGranted] with `canRequest = true`, `denied` or `restricted` to `canRequest
 * = false`, and an unrecognized value to `canRequest = null`.
 * [`CLLocationManager.accuracyAuthorization`](https://developer.apple.com/documentation/corelocation/cllocationmanager/accuracyauthorization)
 * distinguishes precise from approximate grants. A request calls
 * [`requestWhenInUseAuthorization()`](https://developer.apple.com/documentation/corelocation/cllocationmanager/requestwheninuseauthorization())
 * and starts location updates so macOS can present the system prompt.
 *
 * If the manager cannot be allocated, [status] reports [LocationPermission.Granted] at
 * [LocationAccuracyAuthorization.Unknown] so that collection still reaches the provider's guarded
 * update path, which retries the allocation and reports a persistent failure as
 * [LocationUnavailableReason.UnexpectedFailure].
 */
public class MacosLocationPermissionRequester
internal constructor(private val client: CoreLocationClient) : AutoCloseable {
  public constructor() : this(SystemCoreLocationClient())

  /** Whether the process has a usable Core Location implementation. */
  public val backendAvailability: LocationBackendAvailability = client.backendAvailability
  private val mutableStatus =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))

  /** Current foreground location permission, updated when Core Location reports a change. */
  public val status: StateFlow<LocationPermission> = mutableStatus
  private val requestPending = AtomicBoolean()

  // Allocation is fallible and must not throw from construction, so the manager is created through
  // manager() and retried on each access until an attempt succeeds. A failed attempt reports
  // permission as granted at unknown accuracy, so that rememberLocationState still collects updates
  // and the provider's own guarded allocation retries or reports UnexpectedFailure.
  private var manager: CoreLocationManager? = null

  private fun manager(): CoreLocationManager? =
    manager
      ?: try {
        client.createManager().also {
          it.setDelegate(delegate)
          manager = it
          mutableStatus.value = readPermission(it.authorizationStatus, it.accuracyAuthorization)
        }
      } catch (error: Throwable) {
        null
      }

  private val delegate =
    object : CoreLocationDelegate {
      override fun didUpdateLocations(locations: List<CoreLocationMeasurement>) = Unit

      override fun didFailWithError(error: CoreLocationError) {
        manager?.stopUpdatingLocation()
        requestPending.set(false)
      }

      override fun didChangeAuthorization() {
        val permission = currentPermission()
        mutableStatus.value = permission
        if (permission != LocationPermission.NotGranted(canRequest = true)) {
          manager?.stopUpdatingLocation()
        }
        requestPending.set(false)
      }
    }

  init {
    if (manager() == null) {
      mutableStatus.value = LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
    }
  }

  /**
   * Starts a foreground permission request and returns immediately. The result is published to
   * [status].
   */
  public fun requestForegroundPermission() {
    if (backendAvailability != LocationBackendAvailability.Available) return
    val manager = manager() ?: return
    val current = currentPermission()
    if (current != LocationPermission.NotGranted(canRequest = true)) return
    if (!requestPending.compareAndSet(false, true)) return
    manager.requestWhenInUseAuthorization()
    // macOS presents the system prompt when a location service starts, not from the
    // authorization request alone.
    manager.startUpdatingLocation()
  }

  /** Releases the Core Location manager and client. */
  override fun close() {
    manager?.close()
    client.close()
  }

  private fun currentPermission(): LocationPermission =
    manager?.let { readPermission(it.authorizationStatus, it.accuracyAuthorization) }
      ?: LocationPermission.NotGranted(canRequest = null)
}
