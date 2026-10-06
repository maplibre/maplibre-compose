package org.maplibre.compose.location.desktop.macos

import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.location.DesktopLocationBackend
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

  override fun createProvider(window: XdgPortalWindow?): LocationProvider = MacosLocationProvider()
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
) : LocationProvider {
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
    val collection = scope.launch {
      try {
        currentCoroutineContext().ensureActive()
        check(backendAvailability == LocationBackendAvailability.Available) {
          "Location updates require an available backend: $backendAvailability"
        }
        refreshPermission().collect { send(it) }
        permission.collectLatest { status ->
          when (status) {
            is LocationPermission.Granted -> {
              collectUpdates(request).collect { send(it) }
              channel.close()
              this@launch.cancel()
            }
            LocationPermission.Unknown -> refreshPermission().collect { send(it) }
            is LocationPermission.NotGranted -> {
              val enabled = withContext(ioDispatcher) { client.locationServicesEnabled }
              send(
                LocationEvent.Unavailable(
                  if (enabled) LocationUnavailableReason.PermissionDenied
                  else LocationUnavailableReason.ServicesDisabled
                )
              )
            }
            // This provider reports only the permissions above.
            else -> Unit
          }
        }
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

  private fun refreshPermission(): Flow<LocationEvent> =
    flow<LocationEvent> { requester.refreshPermission() }
      .retryWhen { error, _ ->
        if (error is CancellationException) return@retryWhen false
        emit(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
        delay(1.seconds)
        true
      }

  private fun collectUpdates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
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
      if (error.domain == kCLErrorDomain && error.code == kCLErrorDenied) {
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
          location.asMaplibreLocationMeasurement(),
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

  // Confined to the location thread. Core Location calls the delegate there too, so reads,
  // requests, callbacks, and close never interleave. Only reentrant calls can nest; `depth` defers
  // releasing the manager until the outermost call returns.
  private var manager: CoreLocationManager? = null
  private var requestPending = false
  private var closed = false
  private var released = false
  private var depth = 0

  private fun <T> onLocationThread(action: () -> T): T = client.onLocationThread {
    depth += 1
    try {
      action()
    } finally {
      depth -= 1
      if (closed && depth == 0) release()
    }
  }

  private fun release() {
    if (released) return
    released = true
    try {
      manager?.close()
    } finally {
      manager = null
      client.close()
    }
  }

  private fun checkOpen() = check(!closed) { "The macOS permission requester is closed" }

  private fun manager(): CoreLocationManager {
    manager?.let {
      return it
    }
    val created = client.createManager()
    try {
      created.setDelegate(delegate(created))
    } catch (error: Throwable) {
      created.close()
      throw error
    }
    manager = created
    return created
  }

  private fun delegate(source: CoreLocationManager): CoreLocationDelegate =
    object : CoreLocationDelegate {
      override fun didUpdateLocations(locations: List<CoreLocationMeasurement>) = Unit

      override fun didFailWithError(error: CoreLocationError) = onLocationThread {
        if (closed || manager !== source) return@onLocationThread
        source.stopUpdatingLocation()
        requestPending = false
      }

      override fun didChangeAuthorization() = onLocationThread {
        if (closed || manager !== source) return@onLocationThread
        val permission = runCatching { readAndPublishPermission(source) }
        if (permission.getOrNull() != LocationPermission.NotGranted(canRequest = true)) {
          source.stopUpdatingLocation()
        }
        requestPending = false
      }
    }

  init {
    runCatching { refreshPermission() }
  }

  internal fun refreshPermission(): LocationPermission = onLocationThread {
    checkOpen()
    val manager =
      try {
        manager()
      } catch (error: Throwable) {
        mutableStatus.value = LocationPermission.Unknown
        throw error
      }
    readAndPublishPermission(manager)
  }

  private fun readAndPublishPermission(source: CoreLocationManager): LocationPermission {
    val permission =
      try {
        readPermission(source.authorizationStatus, source.accuracyAuthorization)
      } catch (error: Throwable) {
        mutableStatus.value = LocationPermission.Unknown
        throw error
      }
    mutableStatus.value = permission
    return permission
  }

  /**
   * Starts a foreground permission request and returns immediately. The result is published to
   * [status].
   */
  public fun requestForegroundPermission(): Unit = onLocationThread {
    checkOpen()
    if (backendAvailability != LocationBackendAvailability.Available) return@onLocationThread
    if (requestPending) return@onLocationThread
    // Claim the request before refreshing: publishing the status can run a collector that
    // requests again on this thread.
    requestPending = true
    try {
      val permission = runCatching { refreshPermission() }.getOrNull()
      if (permission != LocationPermission.NotGranted(canRequest = true)) {
        requestPending = false
        return@onLocationThread
      }
      val manager = manager()
      manager.requestWhenInUseAuthorization()
      // macOS presents the prompt when location updates start.
      manager.startUpdatingLocation()
    } catch (error: Throwable) {
      requestPending = false
      throw error
    }
  }

  /** Releases the Core Location manager and client after active calls finish. */
  override fun close(): Unit = onLocationThread { closed = true }
}
