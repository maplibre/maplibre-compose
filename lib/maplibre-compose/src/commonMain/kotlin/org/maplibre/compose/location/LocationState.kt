package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.TimeMark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Rotation
import org.maplibre.spatialk.units.extensions.degrees

/**
 * Lifecycle-aware state for foreground location and heading tracking.
 *
 * [lastLocation] and [lastHeading] retain their most recently received measurements when collection
 * stops. [status] separately describes whether updates are active, so retained data is not mistaken
 * for a live update.
 */
@Stable
public class LocationState
internal constructor(initialPermission: LocationPermission = LocationPermission.NotGranted(null)) {
  /** The user's last known location measurement. */
  public var lastLocation: LocationMeasurement? by mutableStateOf(null)
    internal set

  /** The device's last known heading. */
  public var lastHeading: HeadingMeasurement? by mutableStateOf(null)
    internal set

  /** Process-local monotonic mark for [lastLocation], or `null` before the first measurement. */
  public var lastLocationMeasurementMark: TimeMark? by mutableStateOf(null)
    internal set

  /** Current foreground location authorization. */
  public var permission: LocationPermission by mutableStateOf(initialPermission)
    internal set

  /** Current foreground tracking state. */
  public var status: LocationTrackingStatus by mutableStateOf(LocationTrackingStatus.Stopped)
    internal set

  /** Current device-heading tracking state. */
  public var headingStatus: HeadingTrackingStatus by mutableStateOf(HeadingTrackingStatus.Stopped)
    internal set

  internal var requestPermissionAction: () -> Unit = {}

  internal var retryKey: Int by mutableStateOf(0)
    private set

  /** Requests foreground permission; the result is published to [permission]. */
  public fun requestPermission() {
    requestPermissionAction()
  }

  /**
   * Restarts a collection that ended in [LocationTrackingStatus.Unavailable].
   *
   * A provider may end its updates on a condition it cannot observe changing, such as the macOS
   * location services toggle, which the user can flip without the window ever losing its lifecycle
   * state. Call this when the user asks to try again.
   */
  public fun retry() {
    retryKey++
  }

  internal fun accept(event: LocationEvent.Update) {
    lastLocation = event.measurement
    lastLocationMeasurementMark = event.measurementMark
    status = LocationTrackingStatus.Tracking
  }
}

/** Current state of device-heading collection managed by [rememberLocationState]. */
public sealed interface HeadingTrackingStatus {
  /** No platform heading request is active. */
  public data object Stopped : HeadingTrackingStatus

  /** A platform heading request is active and has not delivered a measurement. */
  public data object Starting : HeadingTrackingStatus

  /** The active heading request has delivered a measurement. */
  public data object Tracking : HeadingTrackingStatus

  /** The heading provider failed unexpectedly. */
  public data class Unavailable(val cause: Throwable) : HeadingTrackingStatus
}

/** Current state of the foreground location updates managed by [rememberLocationState]. */
public sealed interface LocationTrackingStatus {
  /** No platform location request is active. */
  public data object Stopped : LocationTrackingStatus

  /** Tracking is enabled but foreground permission is not granted. */
  public data object WaitingForPermission : LocationTrackingStatus

  /** A platform location request is active and has not delivered its first measurement. */
  public data object Starting : LocationTrackingStatus

  /** The active location request has delivered at least one measurement. */
  public data object Tracking : LocationTrackingStatus

  /** An expected or unexpected condition currently prevents delivery. */
  public data class Unavailable(
    val reason: LocationUnavailableReason,
    val cause: Throwable? = null,
  ) : LocationTrackingStatus
}

/**
 * Remembers foreground location and heading state.
 *
 * Location updates are collected while [enabled] is `true` and the lifecycle is active. This
 * function never requests permission automatically; the application chooses when to call
 * [LocationState.requestPermission]. Stopping collection releases the platform request while
 * retaining the last measurements in [LocationState]. An unsupported or misconfigured provider is
 * reported through [LocationState.status] before permission is requested.
 *
 * @param enabled Whether location and heading updates should run while lifecycle-active.
 * @param provider The [LocationProvider] to use for obtaining location updates and for observing
 *   and requesting foreground location permission. A custom provider whose
 *   [LocationProvider.permission] keeps the granted default needs no permission handling.
 * @param request Preferences for location updates.
 * @param headingProvider The optional [HeadingProvider] to use for obtaining device-heading
 *   updates. By default, a provider that emits no headings is used.
 * @param headingRequest Preferences for device-heading updates.
 * @param lifecycleOwner The [LifecycleOwner] to scope the collection of updates to. Defaults to the
 *   current [LocalLifecycleOwner].
 * @param minActiveState The minimum [Lifecycle.State] at which to collect updates. Defaults to
 *   [Lifecycle.State.STARTED].
 * @param coroutineContext The [CoroutineContext] to use for collecting updates. Defaults to
 *   [EmptyCoroutineContext].
 * @return A remembered [LocationState] instance.
 */
@Composable
public fun rememberLocationState(
  enabled: Boolean = true,
  provider: LocationProvider = rememberDefaultLocationProvider(),
  request: LocationRequest = LocationRequest(),
  headingProvider: HeadingProvider = NoHeadingProvider,
  headingRequest: HeadingRequest = HeadingRequest(),
  lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
  minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
  coroutineContext: CoroutineContext = EmptyCoroutineContext,
): LocationState {
  val state = remember(provider) { LocationState(provider.permission.value) }
  val permission by provider.permission.collectAsState()
  SideEffect {
    state.permission = permission
    state.requestPermissionAction = provider::requestPermission
  }

  LaunchedEffect(
    enabled,
    provider,
    request,
    permission,
    state,
    state.retryKey,
    lifecycleOwner.lifecycle,
    minActiveState,
    coroutineContext,
  ) {
    when (val availability = provider.backendAvailability) {
      is LocationBackendAvailability.Misconfigured -> {
        state.status =
          LocationTrackingStatus.Unavailable(
            LocationUnavailableReason.Misconfigured,
            availability.cause,
          )
      }
      LocationBackendAvailability.Unsupported -> {
        state.status = LocationTrackingStatus.Unavailable(LocationUnavailableReason.Unsupported)
      }
      LocationBackendAvailability.Available ->
        when {
          !enabled -> state.status = LocationTrackingStatus.Stopped
          permission !is LocationPermission.Granted -> {
            state.status = LocationTrackingStatus.WaitingForPermission
          }
          else -> {
            state.status = LocationTrackingStatus.Stopped
            lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
              try {
                state.status = LocationTrackingStatus.Starting
                val collectSession: suspend () -> Unit = {
                  provider
                    .updates(request)
                    .catch { error ->
                      if (error is CancellationException) throw error
                      emit(
                        LocationEvent.Unavailable(
                          LocationUnavailableReason.UnexpectedFailure,
                          error,
                        )
                      )
                    }
                    .collect { event ->
                      when (event) {
                        is LocationEvent.Update -> state.accept(event)
                        is LocationEvent.Unavailable ->
                          state.status =
                            LocationTrackingStatus.Unavailable(event.reason, event.cause)
                      }
                    }
                }
                if (coroutineContext == EmptyCoroutineContext) collectSession()
                else withContext(coroutineContext) { collectSession() }
                if (
                  state.status == LocationTrackingStatus.Starting ||
                    state.status == LocationTrackingStatus.Tracking
                ) {
                  state.status = LocationTrackingStatus.Stopped
                }
              } catch (error: CancellationException) {
                state.status = LocationTrackingStatus.Stopped
                throw error
              }
            }
          }
        }
    }
  }

  LaunchedEffect(
    enabled,
    headingProvider,
    headingRequest,
    permission,
    state,
    lifecycleOwner.lifecycle,
    minActiveState,
    coroutineContext,
    state.retryKey,
  ) {
    if (!enabled || permission !is LocationPermission.Granted) {
      state.headingStatus = HeadingTrackingStatus.Stopped
      return@LaunchedEffect
    }
    lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
      try {
        val collectHeading: suspend () -> Unit = {
          state.headingStatus = HeadingTrackingStatus.Starting
          headingProvider
            .updates(headingRequest)
            .catch { error -> state.headingStatus = HeadingTrackingStatus.Unavailable(error) }
            .collect { heading ->
              state.lastHeading = heading
              state.headingStatus = HeadingTrackingStatus.Tracking
            }
        }
        if (coroutineContext == EmptyCoroutineContext) collectHeading()
        else withContext(coroutineContext) { collectHeading() }
        if (
          state.headingStatus == HeadingTrackingStatus.Starting ||
            state.headingStatus == HeadingTrackingStatus.Tracking
        ) {
          state.headingStatus = HeadingTrackingStatus.Stopped
        }
      } catch (error: CancellationException) {
        state.headingStatus = HeadingTrackingStatus.Stopped
        throw error
      }
    }
  }

  return state
}

/**
 * Returns the most accurate bearing measurement available.
 *
 * This function considers bearings from two potential sources:
 * 1. The [LocationMeasurement] course indicates the direction of travel.
 * 2. The device [HeadingMeasurement] indicates the direction that the top of the device faces.
 *
 * It compares the accuracy of these two measurements and returns the one with the smallest accuracy
 * value (i.e., the most precise). If a measurement has no accuracy specified (`null`), it is
 * treated as having infinite (the worst possible) accuracy.
 *
 * @return The bearing with the highest accuracy, or `null` when neither source provides a bearing.
 */
public fun LocationState.mostAccurateBearing(): Bearing? = mostAccurateBearingMeasurement()?.bearing

/** Returns the estimated error of [mostAccurateBearing], or `null` when unknown. */
public fun LocationState.mostAccurateBearingAccuracy(): Rotation? =
  mostAccurateBearingMeasurement()?.accuracy

internal data class BearingMeasurement(val bearing: Bearing, val accuracy: Rotation?)

private fun LocationState.mostAccurateBearingMeasurement(): BearingMeasurement? =
  selectMostAccurateBearing(
    lastLocation?.course?.let { BearingMeasurement(it, lastLocation?.courseAccuracy) },
    lastHeading?.let { BearingMeasurement(it.bearing, it.accuracy) },
  )

internal fun selectMostAccurateBearing(
  first: BearingMeasurement?,
  second: BearingMeasurement?,
): BearingMeasurement? =
  listOfNotNull(first, second).minByOrNull { it.accuracy ?: Double.POSITIVE_INFINITY.degrees }
