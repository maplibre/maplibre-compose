package org.maplibre.compose.location.desktop.macos

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.DesktopLocationProvider
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

  private val job = SupervisorJob()
  private val scope = CoroutineScope(dispatcher + job)
  private val requester = MacosLocationPermissionRequester(client)

  init {
    job.invokeOnCompletion { requester.close() }
  }

  override val backendAvailability: LocationBackendAvailability = client.backendAvailability

  override val permission: StateFlow<LocationPermission>
    get() = requester.status

  override fun requestPermission() {
    check(job.isActive) { "The macOS location provider is closed" }
    requester.requestForegroundPermission()
  }

  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    check(job.isActive) { "The macOS location provider is closed" }
    val collection =
      scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
          currentCoroutineContext().ensureActive()
          collectUpdates(request).collect { send(it) }
        } catch (error: Throwable) {
          if (job.isActive) channel.close(error)
        }
      }
    collection.invokeOnCompletion { channel.close() }
    try {
      awaitClose()
    } finally {
      withContext(NonCancellable) { collection.cancelAndJoin() }
    }
  }

  private fun collectUpdates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    check(backendAvailability == LocationBackendAvailability.Available) {
      "Location updates require an available backend: $backendAvailability"
    }
    val locationServicesEnabled = withContext(ioDispatcher) { client.locationServicesEnabled }
    if (!locationServicesEnabled) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.ServicesDisabled))
      close()
      return@callbackFlow
    }

    val permission =
      try {
        requester.refreshPermission()
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
        close()
        return@callbackFlow
      }
    currentCoroutineContext().ensureActive()
    if (permission !is LocationPermission.Granted) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied))
      close()
      return@callbackFlow
    }

    val manager =
      try {
        client.createManager()
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
        close()
        return@callbackFlow
      }

    try {
      currentCoroutineContext().ensureActive()
      val delegate = UpdateDelegate(this, channel, client, ioDispatcher)
      manager.setDelegate(delegate)
      manager.desiredAccuracy = request.accuracy.toDesiredAccuracy()
      manager.distanceFilter = request.minimumDistance.inMeters
      manager.location?.let(delegate::sendLocation)
      manager.startUpdatingLocation()
      awaitClose()
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
      close()
    } finally {
      manager.close()
    }
  }
    .flowOn(dispatcher)

  override fun close() {
    job.cancel()
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
 * If permission initialization fails, [status] reports [LocationPermission.Unknown]. Collecting
 * location updates retries initialization without requesting permission. Persistent failures report
 * [LocationUnavailableReason.UnexpectedFailure].
 */
public class MacosLocationPermissionRequester
internal constructor(private val client: CoreLocationClient) : AutoCloseable {
  public constructor() : this(SystemCoreLocationClient())

  /** Whether the process has a usable Core Location implementation. */
  public val backendAvailability: LocationBackendAvailability = client.backendAvailability
  private val mutableStatus = MutableStateFlow<LocationPermission>(LocationPermission.Unknown)

  /** Current foreground location permission, updated when Core Location reports a change. */
  public val status: StateFlow<LocationPermission> = mutableStatus
  private val requestPending = AtomicBoolean()
  private val lock = Any()
  private var closed = false
  private var activeOperations = 0
  private var disposed = false
  private var permissionRevision = 0L
  private var permissionFailure: Throwable? = null

  private fun withClient(ifClosed: () -> Unit = {}, action: () -> Unit) {
    val accepted =
      synchronized(lock) {
        if (closed) false
        else {
          activeOperations += 1
          true
        }
      }
    if (!accepted) return ifClosed()
    try {
      action()
    } finally {
      val dispose =
        synchronized(lock) {
          activeOperations -= 1
          claimDisposal()
        }
      if (dispose) disposeClient()
    }
  }

  private fun claimDisposal(): Boolean =
    if (closed && activeOperations == 0 && !disposed) {
      disposed = true
      true
    } else false

  private fun disposeClient() {
    try {
      manager?.close()
    } finally {
      manager = null
      client.close()
    }
  }

  private var manager: CoreLocationManager? = null

  private fun manager(): CoreLocationManager {
    synchronized(lock) { manager }
      ?.let {
        return it
      }
    val candidate = client.createManager()
    try {
      candidate.setDelegate(delegate(candidate))
    } catch (error: Throwable) {
      candidate.close()
      throw error
    }
    val selected = synchronized(lock) { manager ?: candidate.also { manager = it } }
    if (selected !== candidate) candidate.close()
    return selected
  }

  private fun delegate(source: CoreLocationManager): CoreLocationDelegate =
    object : CoreLocationDelegate {
      override fun didUpdateLocations(locations: List<CoreLocationMeasurement>) = Unit

      override fun didFailWithError(error: CoreLocationError) = withClient {
        if (synchronized(lock) { manager !== source }) return@withClient
        source.stopUpdatingLocation()
        requestPending.set(false)
      }

      override fun didChangeAuthorization() = withClient {
        client.onLocationThread {
          if (synchronized(lock) { manager !== source }) return@onLocationThread
          val permission = runCatching {
            readAndPublishPermission(source)
          }
            .getOrDefault(LocationPermission.Unknown)
          if (permission != LocationPermission.NotGranted(canRequest = true)) {
            source.stopUpdatingLocation()
          }
          requestPending.set(false)
        }
      }
    }

  init {
    runCatching { refreshPermission() }
  }

  internal fun refreshPermission(): LocationPermission {
    var permission: LocationPermission = LocationPermission.Unknown
    withClient(ifClosed = { error("The macOS permission requester is closed") }) {
      val revisionBeforeAllocation = synchronized(lock) { permissionRevision }
      val manager =
        try {
          manager()
        } catch (error: Throwable) {
          permission = client.onLocationThread {
            synchronized(lock) {
              if (permissionRevision == revisionBeforeAllocation) {
                permissionRevision += 1
                permissionFailure = error
                mutableStatus.value = LocationPermission.Unknown
              }
              permissionFailure?.let { throw it }
              mutableStatus.value
            }
          }
          return@withClient
        }
      permission = client.onLocationThread { readAndPublishPermission(manager) }
    }
    return permission
  }

  private fun readAndPublishPermission(source: CoreLocationManager): LocationPermission {
    val revision = synchronized(lock) { ++permissionRevision }
    val result = runCatching {
      readPermission(source.authorizationStatus, source.accuracyAuthorization)
    }
    return synchronized(lock) {
      if (revision == permissionRevision) {
        permissionFailure = result.exceptionOrNull()
        mutableStatus.value = result.getOrDefault(LocationPermission.Unknown)
      }
      permissionFailure?.let { throw it }
      mutableStatus.value
    }
  }

  /**
   * Starts a foreground permission request and returns immediately. The result is published to
   * [status].
   */
  public fun requestForegroundPermission(): Unit =
    withClient(ifClosed = { error("The macOS permission requester is closed") }) {
      if (backendAvailability != LocationBackendAvailability.Available) return@withClient
      if (!requestPending.compareAndSet(false, true)) return@withClient
      val permission =
        try {
          refreshPermission()
        } catch (error: Throwable) {
          requestPending.set(false)
          return@withClient
        }
      if (permission != LocationPermission.NotGranted(canRequest = true)) {
        requestPending.set(false)
        return@withClient
      }
      val manager = manager()
      try {
        manager.requestWhenInUseAuthorization()
        // macOS presents the prompt when location updates start.
        manager.startUpdatingLocation()
      } catch (error: Throwable) {
        requestPending.set(false)
        throw error
      }
    }

  /** Releases the Core Location manager and client after active calls finish. */
  override fun close() {
    val dispose =
      synchronized(lock) {
        closed = true
        claimDisposal()
      }
    if (dispose) disposeClient()
  }
}
