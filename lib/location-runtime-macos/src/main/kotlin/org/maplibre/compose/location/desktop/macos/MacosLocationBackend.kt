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
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationProviderAvailability
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationServicesStatus
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
 * A [LocationProvider] built on
 * [`CLLocationManager`](https://developer.apple.com/documentation/corelocation/cllocationmanager).
 *
 * [LocationProvider.permission] and [LocationProvider.requestPermission] delegate to a
 * [MacosLocationPermissionRequester] that shares the provider's Core Location client.
 *
 * Each collection creates a
 * [`CLLocationManager`](https://developer.apple.com/documentation/corelocation/cllocationmanager)
 * on the AppKit main run loop, applies the request's accuracy and distance preferences, and stops
 * the manager when collection ends. Compose Desktop's `Dispatchers.Main` is the Swing
 * event-dispatch thread on the AWT host and does not pump that run loop, so Core Location work is
 * marshaled there explicitly. The process's app Info.plist must declare
 * `NSLocationWhenInUseUsageDescription`. [LocationRequest.minimumInterval] is ignored because Core
 * Location filters only by distance.
 *
 * [LocationAccuracy.BestForNavigation] maps to
 * [`kCLLocationAccuracyBestForNavigation`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracybestfornavigation),
 * [LocationAccuracy.High] maps to
 * [`kCLLocationAccuracyBest`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracybest),
 * [LocationAccuracy.Balanced] maps to
 * [`kCLLocationAccuracyHundredMeters`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracyhundredmeters),
 * [LocationAccuracy.Low] maps to
 * [`kCLLocationAccuracyKilometer`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracykilometer),
 * and [LocationAccuracy.Lowest] maps to
 * [`kCLLocationAccuracyReduced`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracyreduced).
 *
 * [`kCLErrorDenied`](https://developer.apple.com/documentation/corelocation/clerror/denied) maps to
 * [LocationUnavailableReason.ServicesDisabled] when location services are disabled and to
 * [LocationUnavailableReason.PermissionDenied] otherwise.
 * [`kCLErrorPromptDeclined`](https://developer.apple.com/documentation/corelocation/clerror/promptdeclined)
 * also maps to [LocationUnavailableReason.PermissionDenied].
 * [`kCLErrorLocationUnknown`](https://developer.apple.com/documentation/corelocation/clerror/locationunknown)
 * and [`kCLErrorNetwork`](https://developer.apple.com/documentation/corelocation/clerror/network)
 * map to [LocationUnavailableReason.TemporarilyUnavailable]. Other Core Location errors map to
 * [LocationUnavailableReason.UnexpectedFailure].
 */
public class MacosLocationProvider
internal constructor(
  private val client: CoreLocationClient,
  private val dispatcher: CoroutineContext = Dispatchers.Main,
  private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) : DesktopLocationProvider {
  public constructor() : this(SystemCoreLocationClient())

  private val requester = MacosLocationPermissionRequester(client)
  private val mutableLocationServices = MutableStateFlow(LocationServicesStatus.Unknown)

  override val availability: LocationProviderAvailability = client.backendAvailability

  override val permission: StateFlow<LocationPermission>
    get() = requester.status

  override val locationServices: StateFlow<LocationServicesStatus> = mutableLocationServices

  override fun requestPermission(): Unit = requester.requestForegroundPermission()

  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    when (val availability = client.backendAvailability) {
      is LocationProviderAvailability.Misconfigured -> {
        trySend(
          LocationEvent.Unavailable(LocationUnavailableReason.Misconfigured, availability.cause)
        )
        close()
        return@callbackFlow
      }
      LocationProviderAvailability.Unsupported -> {
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
        close()
        return@callbackFlow
      }
      LocationProviderAvailability.Available -> Unit
    }
    val locationServicesEnabled = withContext(ioDispatcher) { client.locationServicesEnabled }
    if (!locationServicesEnabled) {
      mutableLocationServices.value = LocationServicesStatus.Disabled
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.ServicesDisabled))
      close()
      return@callbackFlow
    }
    mutableLocationServices.value = LocationServicesStatus.Enabled

    val manager =
      try {
        client.createManager()
      } catch (error: Throwable) {
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
        close()
        return@callbackFlow
      }

    try {
      val delegate = UpdateDelegate(this, channel, client, ioDispatcher, mutableLocationServices)
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

    // Retaining the delegate in this closure is required because CLLocationManager does not.
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
    private val locationServices: MutableStateFlow<LocationServicesStatus>,
  ) : CoreLocationDelegate {
    override fun didUpdateLocations(locations: List<CoreLocationMeasurement>) {
      locations.forEach(::sendLocation)
    }

    override fun didFailWithError(error: CoreLocationError) {
      if (error.domain == CL_ERROR_DOMAIN && error.code == CL_ERROR_DENIED) {
        scope.launch(ioDispatcher) {
          val servicesEnabled = client.locationServicesEnabled
          locationServices.value =
            if (servicesEnabled) LocationServicesStatus.Enabled else LocationServicesStatus.Disabled
          channel.trySend(LocationEvent.Unavailable(error.asUnavailableReason(servicesEnabled)))
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
 * [LocationPermission.Required] with `canRequest = true`, `denied` or `restricted` to `canRequest =
 * false`, and an unrecognized value to `canRequest = null`.
 * [`CLLocationManager.accuracyAuthorization`](https://developer.apple.com/documentation/corelocation/cllocationmanager/accuracyauthorization)
 * distinguishes precise from approximate grants. A request calls
 * [`requestWhenInUseAuthorization()`](https://developer.apple.com/documentation/corelocation/cllocationmanager/requestwheninuseauthorization())
 * and starts location updates so macOS can present the system prompt.
 *
 * If the manager cannot be allocated, [status] remains [LocationPermission.Unknown]. A later
 * permission request retries the allocation.
 */
public class MacosLocationPermissionRequester
internal constructor(private val client: CoreLocationClient) : AutoCloseable {
  public constructor() : this(SystemCoreLocationClient())

  private val mutableStatus = MutableStateFlow<LocationPermission>(LocationPermission.Unknown)

  /** Current foreground location permission, updated when Core Location reports a change. */
  public val status: StateFlow<LocationPermission> = mutableStatus
  private val requestPending = AtomicBoolean()

  // Allocation is fallible and must not throw from construction, so manager() retries it when the
  // requester needs the manager.
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
        if (permission != LocationPermission.Required(canRequest = true)) {
          manager?.stopUpdatingLocation()
        }
        requestPending.set(false)
      }
    }

  init {
    manager()
  }

  /**
   * Starts a foreground permission request and returns immediately. The result is published to
   * [status].
   */
  public fun requestForegroundPermission() {
    if (client.backendAvailability != LocationProviderAvailability.Available) return
    val manager = manager() ?: return
    val current = currentPermission()
    if (current != LocationPermission.Required(canRequest = true)) return
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
      ?: LocationPermission.Unknown
}
