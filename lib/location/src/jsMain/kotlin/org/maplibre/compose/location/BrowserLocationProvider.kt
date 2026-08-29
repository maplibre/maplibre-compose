package org.maplibre.compose.location

import js.objects.unsafeJso
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters
import web.events.EventHandler
import web.geolocation.GeolocationPosition
import web.geolocation.GeolocationPositionError
import web.geolocation.PositionOptions
import web.navigator.navigator
import web.permissions.PermissionDescriptor
import web.permissions.PermissionName
import web.permissions.PermissionState
import web.permissions.denied
import web.permissions.geolocation
import web.permissions.granted
import web.permissions.prompt
import web.permissions.query

/**
 * A browser location provider backed by the
 * [Geolocation API](https://developer.mozilla.org/en-US/docs/Web/API/Geolocation_API).
 *
 * [LocationAccuracy.BestForNavigation] and [LocationAccuracy.High] set
 * [`PositionOptions.enableHighAccuracy`](https://developer.mozilla.org/en-US/docs/Web/API/Geolocation/getCurrentPosition#enablehighaccuracy)
 * to `true`. The remaining accuracy values set it to `false`. The provider applies
 * [LocationRequest.minimumInterval] after delivery. It ignores [LocationRequest.minimumDistance]
 * because [`PositionOptions`](https://developer.mozilla.org/en-US/docs/Web/API/PositionOptions) has
 * no distance threshold.
 *
 * A missing Geolocation API maps [LocationProvider.backendAvailability] to
 * [LocationBackendAvailability.Unsupported], and collection emits
 * [LocationUnavailableReason.Unsupported].
 * [`GeolocationPositionError.PERMISSION_DENIED`](https://developer.mozilla.org/en-US/docs/Web/API/GeolocationPositionError/code#geolocationpositionerror.permission_denied)
 * maps to [LocationUnavailableReason.PermissionDenied].
 * [`POSITION_UNAVAILABLE`](https://developer.mozilla.org/en-US/docs/Web/API/GeolocationPositionError/code#geolocationpositionerror.position_unavailable)
 * and
 * [`TIMEOUT`](https://developer.mozilla.org/en-US/docs/Web/API/GeolocationPositionError/code#geolocationpositionerror.timeout)
 * map to [LocationUnavailableReason.TemporarilyUnavailable]. An exception thrown while starting the
 * live watch maps to [LocationUnavailableReason.UnexpectedFailure].
 *
 * [LocationProvider.permission] and [LocationProvider.requestPermission] delegate to a
 * [BrowserLocationPermissionRequester] that shares the provider's boundary, so a permission denial
 * during an active watch updates [LocationProvider.permission].
 */
public class BrowserLocationProvider
internal constructor(
  private val boundary: BrowserGeolocationBoundary,
  coroutineScope: CoroutineScope,
) : LocationProvider {
  /**
   * Creates a provider that observes permission in a coroutine scope that lives as long as the
   * provider.
   */
  public constructor() :
    this(BrowserGeolocation, CoroutineScope(SupervisorJob() + Dispatchers.Default))

  /** Creates a provider that observes permission in [coroutineScope]. */
  public constructor(coroutineScope: CoroutineScope) : this(BrowserGeolocation, coroutineScope)

  private val requester = BrowserLocationPermissionRequester(boundary, coroutineScope)

  override val backendAvailability: LocationBackendAvailability =
    if (boundary.supported) {
      LocationBackendAvailability.Available
    } else {
      LocationBackendAvailability.Unsupported
    }

  override val permission: StateFlow<LocationPermission>
    get() = requester.status

  override fun requestPermission(): Unit = requester.requestForegroundPermission()

  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    if (!boundary.supported) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
      close()
      return@callbackFlow
    }
    var previous: BrowserPosition? = null
    fun publish(result: BrowserResult) {
      when (result) {
        is BrowserResult.Position -> {
          val current = result.value
          if (
            previous == null ||
              current.capturedAt - previous!!.capturedAt >= request.minimumInterval
          ) {
            previous = current
            trySend(LocationEvent.Fix(current.asLocationFix()))
          }
        }
        is BrowserResult.Error -> {
          val reason = result.value.asUnavailableReason()
          previous = null
          trySend(LocationEvent.Unavailable(reason))
          if (reason == LocationUnavailableReason.PermissionDenied) {
            boundary.permissionState.acceptDenial()
            close()
          }
        }
      }
    }

    val stop =
      try {
        boundary.startWatch(request.asBrowserOptions(), ::publish)
      } catch (error: Throwable) {
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
        close()
        null
      }

    awaitClose { stop?.invoke() }
  }
}

/**
 * Observes and requests browser geolocation permission.
 *
 * [BrowserLocationProvider] delegates [LocationProvider.permission] and
 * [LocationProvider.requestPermission] to an instance of this class. Construct one with a
 * [CoroutineScope] to back a custom [LocationProvider], or use
 * [rememberBrowserLocationPermissionRequester] to bind permission observation to the composition.
 */
public class BrowserLocationPermissionRequester
internal constructor(
  private val boundary: BrowserGeolocationBoundary,
  private val coroutineScope: CoroutineScope,
) {
  /**
   * Creates a requester whose permission observation runs in [coroutineScope]. Cancelling the scope
   * stops the observation.
   */
  public constructor(coroutineScope: CoroutineScope) : this(BrowserGeolocation, coroutineScope)

  /** Whether the browser has a usable Geolocation API. */
  public val backendAvailability: LocationBackendAvailability =
    if (boundary.supported) {
      LocationBackendAvailability.Available
    } else {
      LocationBackendAvailability.Unsupported
    }

  /** Current foreground location permission. */
  public val status: StateFlow<LocationPermission> = boundary.permissionState.status

  private var requestPending = false

  init {
    coroutineScope.launch {
      boundary
        .permissionChanges()
        .catch { emit(BrowserPermission.Unknown) }
        .collect { boundary.permissionState.accept(it.asLocationPermission()) }
    }
  }

  /**
   * Starts a foreground permission request and returns immediately.
   *
   * The result is published to [status]. Calls made while a request is active do not start another
   * browser request.
   */
  public fun requestForegroundPermission() {
    val current = status.value
    if (
      !boundary.supported ||
        current is LocationPermission.Granted ||
        current == LocationPermission.NotGranted(canRequest = false) ||
        requestPending
    ) {
      return
    }
    requestPending = true
    coroutineScope.launch {
      try {
        when (
          val result =
            boundary.requestPosition(
              BrowserOptions(highAccuracy = true, timeout = PERMISSION_PROBE_TIMEOUT)
            )
        ) {
          is BrowserResult.Position ->
            boundary.permissionState.accept(
              LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
            )
          is BrowserResult.Error ->
            if (result.value == BrowserError.PermissionDenied) {
              boundary.permissionState.acceptDenial()
            } else {
              boundary.permissionState.accept(
                LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
              )
            }
        }
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        boundary.permissionState.accept(LocationPermission.NotGranted(canRequest = null))
      } finally {
        requestPending = false
      }
    }
  }

  private companion object {
    private val PERMISSION_PROBE_TIMEOUT = 1.seconds
  }
}

/**
 * Creates and remembers the browser geolocation permission requester.
 *
 * [`PermissionStatus.state`](https://developer.mozilla.org/en-US/docs/Web/API/PermissionStatus/state)
 * maps `granted` to [LocationPermission.Granted], `prompt` to [LocationPermission.NotGranted] with
 * `canRequest = true`, and `denied` to `canRequest = false`. A browser without the Permissions API
 * reports `canRequest = null` until an explicit request determines the result. A missing
 * Geolocation API maps [BrowserLocationPermissionRequester.backendAvailability] to
 * [LocationBackendAvailability.Unsupported].
 */
internal enum class BrowserPermission {
  Unknown,
  Prompt,
  Granted,
  Denied,
}

internal enum class BrowserError {
  PermissionDenied,
  PositionUnavailable,
  Timeout,
  Unknown,
}

internal data class BrowserOptions(
  val highAccuracy: Boolean,
  val timeout: Duration? = null,
)

internal data class BrowserPosition(
  val longitude: Double,
  val latitude: Double,
  val altitude: Double?,
  val horizontalAccuracyMeters: Double,
  val altitudeAccuracyMeters: Double?,
  val speedMetersPerSecond: Double?,
  val headingDegrees: Double?,
  val capturedAt: Instant,
)

internal sealed interface BrowserResult {
  data class Position(val value: BrowserPosition) : BrowserResult

  data class Error(val value: BrowserError) : BrowserResult
}

internal class BrowserLocationPermissionState {
  private val mutableStatus =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))
  val status: StateFlow<LocationPermission> = mutableStatus

  fun accept(permission: LocationPermission) {
    mutableStatus.value = permission
  }

  fun acceptDenial() {
    if (mutableStatus.value is LocationPermission.Granted) {
      mutableStatus.value = LocationPermission.NotGranted(canRequest = null)
    }
  }
}

internal interface BrowserGeolocationBoundary {
  val supported: Boolean
  val permissionState: BrowserLocationPermissionState

  fun permissionChanges(): Flow<BrowserPermission>

  suspend fun requestPosition(options: BrowserOptions): BrowserResult

  fun startWatch(options: BrowserOptions, onResult: (BrowserResult) -> Unit): (() -> Unit)
}

private object BrowserGeolocation : BrowserGeolocationBoundary {
  private val rawNavigator: dynamic = js("navigator")
  override val permissionState = BrowserLocationPermissionState()

  override val supported: Boolean
    get() = rawNavigator.geolocation != null

  override fun permissionChanges(): Flow<BrowserPermission> = callbackFlow {
    if (!supported || rawNavigator.permissions?.query == null) {
      trySend(BrowserPermission.Unknown)
      awaitCancellation()
    }

    val permissionStatus =
      try {
        navigator.permissions.query(
          unsafeJso<PermissionDescriptor> { name = PermissionName.geolocation }
        )
      } catch (_: Throwable) {
        trySend(BrowserPermission.Unknown)
        awaitCancellation()
      }

    fun publish() {
      trySend(permissionStatus.state.asBrowserPermission())
    }
    permissionStatus.onchange = EventHandler { publish() }
    publish()
    awaitClose { permissionStatus.onchange = null }
  }

  override suspend fun requestPosition(options: BrowserOptions): BrowserResult {
    if (!supported) return BrowserResult.Error(BrowserError.Unknown)
    return suspendCancellableCoroutine { continuation ->
      navigator.geolocation.getCurrentPositionWithCallbacks(
        successCallback = { position ->
          if (continuation.isActive) continuation.resume(BrowserResult.Position(position.toValue()))
        },
        errorCallback = { error ->
          if (continuation.isActive) continuation.resume(BrowserResult.Error(error.toValue()))
        },
        options = options.toPositionOptions(),
      )
    }
  }

  override fun startWatch(
    options: BrowserOptions,
    onResult: (BrowserResult) -> Unit,
  ): () -> Unit {
    val watchId =
      navigator.geolocation.watchPositionWithCallbacks(
        successCallback = { onResult(BrowserResult.Position(it.toValue())) },
        errorCallback = { onResult(BrowserResult.Error(it.toValue())) },
        options = options.toPositionOptions(),
      )
    return { navigator.geolocation.clearWatch(watchId) }
  }
}

private fun LocationRequest.asBrowserOptions(): BrowserOptions =
  BrowserOptions(
    highAccuracy =
      accuracy == LocationAccuracy.BestForNavigation || accuracy == LocationAccuracy.High
  )

internal fun BrowserOptions.toPositionOptions(): PositionOptions {
  val result = unsafeJso<PositionOptions> { enableHighAccuracy = highAccuracy }
  val rawResult: dynamic = result
  timeout?.let { rawResult.timeout = it.inWholeMilliseconds.toDouble() }
  return result
}

private fun GeolocationPosition.toValue(): BrowserPosition =
  BrowserPosition(
    longitude = coords.longitude,
    latitude = coords.latitude,
    altitude = coords.altitude,
    horizontalAccuracyMeters = coords.accuracy,
    altitudeAccuracyMeters = coords.altitudeAccuracy,
    speedMetersPerSecond = coords.speed,
    headingDegrees = coords.heading,
    capturedAt = Instant.fromEpochMilliseconds(timestamp.toLong()),
  )

private fun GeolocationPositionError.toValue(): BrowserError =
  when (code) {
    GeolocationPositionError.PERMISSION_DENIED -> BrowserError.PermissionDenied
    GeolocationPositionError.POSITION_UNAVAILABLE -> BrowserError.PositionUnavailable
    GeolocationPositionError.TIMEOUT -> BrowserError.Timeout
  }

private fun BrowserPosition.asLocationFix(): LocationFix =
  LocationFix(
    position = Position(longitude, latitude, altitude),
    horizontalAccuracy = horizontalAccuracyMeters.meters,
    altitudeAccuracy = if (altitude != null) altitudeAccuracyMeters?.meters else null,
    speed = speedMetersPerSecond?.meters,
    course = headingDegrees?.takeIf { it.isFinite() }?.let { Bearing.North + it.degrees },
    measuredAt = capturedAt,
  )

private fun BrowserError.asUnavailableReason(): LocationUnavailableReason =
  when (this) {
    BrowserError.PermissionDenied -> LocationUnavailableReason.PermissionDenied
    BrowserError.PositionUnavailable,
    BrowserError.Timeout -> LocationUnavailableReason.TemporarilyUnavailable
    BrowserError.Unknown -> LocationUnavailableReason.UnexpectedFailure
  }

private fun BrowserPermission.asLocationPermission(): LocationPermission =
  when (this) {
    BrowserPermission.Unknown -> LocationPermission.NotGranted(canRequest = null)
    BrowserPermission.Prompt -> LocationPermission.NotGranted(canRequest = true)
    BrowserPermission.Granted -> LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
    BrowserPermission.Denied -> LocationPermission.NotGranted(canRequest = false)
  }

private fun PermissionState.asBrowserPermission(): BrowserPermission =
  when (this) {
    PermissionState.granted -> BrowserPermission.Granted
    PermissionState.denied -> BrowserPermission.Denied
    PermissionState.prompt -> BrowserPermission.Prompt
  }
