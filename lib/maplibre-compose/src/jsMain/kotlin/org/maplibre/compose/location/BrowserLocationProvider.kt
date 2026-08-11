package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import js.objects.unsafeJso
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * [W3C Geolocation API](https://developer.mozilla.org/en-US/docs/Web/API/Geolocation_API).
 *
 * [LocationAccuracy.BestForNavigation] and [LocationAccuracy.High] set
 * [`PositionOptions.enableHighAccuracy`](https://developer.mozilla.org/en-US/docs/Web/API/Geolocation/getCurrentPosition#enablehighaccuracy)
 * to `true`. The remaining accuracy values set it to `false`. The provider applies
 * [LocationRequest.minimumInterval] after delivery. It ignores [LocationRequest.minimumDistance]
 * because [`PositionOptions`](https://developer.mozilla.org/en-US/docs/Web/API/PositionOptions) has
 * no distance threshold.
 *
 * The browser's
 * [`PermissionStatus.state`](https://developer.mozilla.org/en-US/docs/Web/API/PermissionStatus/state)
 * maps `granted` to [LocationPermission.Granted], `prompt` to [LocationPermission.NotGranted] with
 * `canRequest = true`, and `denied` to `canRequest = false`. A browser without the Permissions API
 * reports `canRequest = null` until an explicit request determines the result.
 *
 * A missing Geolocation API maps to [LocationUnavailableReason.Unsupported].
 * [`GeolocationPositionError.PERMISSION_DENIED`](https://developer.mozilla.org/en-US/docs/Web/API/GeolocationPositionError/code#geolocationpositionerror.permission_denied)
 * maps to [LocationUnavailableReason.PermissionDenied].
 * [`POSITION_UNAVAILABLE`](https://developer.mozilla.org/en-US/docs/Web/API/GeolocationPositionError/code#geolocationpositionerror.position_unavailable)
 * and
 * [`TIMEOUT`](https://developer.mozilla.org/en-US/docs/Web/API/GeolocationPositionError/code#geolocationpositionerror.timeout)
 * map to [LocationUnavailableReason.TemporarilyUnavailable]. An exception thrown while starting or
 * querying geolocation maps to [LocationUnavailableReason.UnexpectedFailure].
 */
public class BrowserLocationProvider
internal constructor(
  private val boundary: BrowserGeolocationBoundary,
  private val permissionController: BrowserLocationPermissionController,
) : LocationProvider {
  override val permission: LocationPermissionController = permissionController
  override val isSupported: Boolean = boundary.supported

  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    if (!boundary.supported) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
      close()
      return@callbackFlow
    }
    if (permissionController.status.value !is LocationPermission.Granted) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied))
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
            trySend(LocationEvent.Fix(current.asLocation()))
          }
        }
        is BrowserResult.Error -> {
          val reason = result.value.asUnavailableReason()
          if (reason == LocationUnavailableReason.PermissionDenied) {
            permissionController.acceptDenied()
          }
          trySend(LocationEvent.Unavailable(reason))
          if (reason == LocationUnavailableReason.PermissionDenied) close()
        }
      }
    }

    val stop =
      try {
        boundary.startWatch(request.asBrowserOptions(maximumAge = Duration.ZERO), ::publish)
      } catch (error: Throwable) {
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
        close()
        null
      }

    val initialFix =
      if (stop != null && request.maximumInitialFixAge != Duration.ZERO) {
        launch {
          try {
            publish(boundary.requestPosition(request.asBrowserOptions()))
          } catch (error: CancellationException) {
            throw error
          } catch (error: Throwable) {
            trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
          }
        }
      } else {
        null
      }

    awaitClose {
      initialFix?.cancel()
      stop?.invoke()
    }
  }
}

/** Creates and remembers the browser location provider. */
@Composable
public fun rememberBrowserLocationProvider(): BrowserLocationProvider {
  val scope = rememberCoroutineScope()
  val permission =
    remember(scope) { BrowserLocationPermissionController(BrowserGeolocation, scope) }
  return remember(permission) { BrowserLocationProvider(BrowserGeolocation, permission) }
}

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider =
  rememberBrowserLocationProvider()

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider = NullOrientationProvider

internal class BrowserLocationPermissionController(
  private val boundary: BrowserGeolocationBoundary,
  coroutineScope: CoroutineScope,
) : LocationPermissionController {
  private val mutableStatus =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))
  override val status: StateFlow<LocationPermission> = mutableStatus

  init {
    coroutineScope.launch {
      boundary
        .permissionChanges()
        .catch { emit(BrowserPermission.Unknown) }
        .collect { mutableStatus.value = it.asLocationPermission() }
    }
  }

  override suspend fun requestForegroundPermission(): LocationPermission {
    if (!boundary.supported) return LocationPermission.NotGranted(canRequest = null)
    when (
      val result =
        boundary.requestPosition(
          BrowserOptions(highAccuracy = true, timeout = PERMISSION_PROBE_TIMEOUT)
        )
    ) {
      is BrowserResult.Position ->
        mutableStatus.value = LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
      is BrowserResult.Error ->
        if (result.value == BrowserError.PermissionDenied) {
          acceptDenied()
        } else {
          mutableStatus.value = LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
        }
    }
    return mutableStatus.value
  }

  internal fun acceptDenied() {
    mutableStatus.value = LocationPermission.NotGranted(canRequest = false)
  }

  private companion object {
    private val PERMISSION_PROBE_TIMEOUT = 1.seconds
  }
}

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
  val maximumAge: Duration? = Duration.ZERO,
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

internal interface BrowserGeolocationBoundary {
  val supported: Boolean

  fun permissionChanges(): Flow<BrowserPermission>

  suspend fun requestPosition(options: BrowserOptions): BrowserResult

  fun startWatch(options: BrowserOptions, onResult: (BrowserResult) -> Unit): (() -> Unit)
}

private object BrowserGeolocation : BrowserGeolocationBoundary {
  private val rawNavigator: dynamic = js("navigator")

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

private fun LocationRequest.asBrowserOptions(
  maximumAge: Duration? = maximumInitialFixAge
): BrowserOptions =
  BrowserOptions(
    highAccuracy =
      accuracy == LocationAccuracy.BestForNavigation || accuracy == LocationAccuracy.High,
    maximumAge = maximumAge,
  )

internal fun BrowserOptions.toPositionOptions(): PositionOptions {
  val result = unsafeJso<PositionOptions> { enableHighAccuracy = highAccuracy }
  val rawResult: dynamic = result
  rawResult.maximumAge = maximumAge?.inWholeMilliseconds?.toDouble() ?: Double.POSITIVE_INFINITY
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

private fun BrowserPosition.asLocation(): Location =
  Location(
    position =
      PositionWithAccuracy(
        Position(longitude, latitude, altitude),
        horizontalAccuracyMeters.meters,
      ),
    altitudeAccuracy = altitudeAccuracyMeters?.meters,
    speed = speedMetersPerSecond?.let { SpeedWithAccuracy(it.meters, accuracy = null) },
    course =
      headingDegrees?.let { BearingWithAccuracy(Bearing.North + it.degrees, accuracy = null) },
    timestamp =
      TimeSource.Monotonic.markNow() -
        (Clock.System.now() - capturedAt).coerceAtLeast(Duration.ZERO),
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
