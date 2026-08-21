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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.units.extensions.degrees

/**
 * Lifecycle-aware state for foreground location and orientation tracking.
 *
 * [location] and [orientation] retain their most recently received measurements when collection
 * stops. [status] separately describes whether updates are active, so retained data is not mistaken
 * for a live update.
 */
@Stable
public class LocationState
internal constructor(initialPermission: LocationPermission = LocationPermission.NotGranted(null)) {
  /** The user's current or last known location. */
  public var location: Location? by mutableStateOf(null)
    internal set

  /** The device's current or last known orientation. */
  public var orientation: Orientation? by mutableStateOf(null)
    internal set

  /** Current foreground location authorization. */
  public var permission: LocationPermission by mutableStateOf(initialPermission)
    internal set

  /** Current foreground tracking state. */
  public var status: LocationTrackingStatus by mutableStateOf(LocationTrackingStatus.Stopped)
    internal set

  internal var requestPermissionAction: () -> Unit = {}

  /** Requests foreground permission; the result is published to [permission]. */
  public fun requestPermission() {
    requestPermissionAction()
  }

  internal fun accept(event: LocationEvent.Fix) {
    location = event.location
    status = LocationTrackingStatus.Tracking
  }
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
 * Remembers foreground location and orientation state.
 *
 * Location updates are collected while [enabled] is `true` and the lifecycle is active. This
 * function never requests permission automatically; the application chooses when to call
 * [LocationState.requestPermission]. Stopping collection releases the platform request while
 * retaining the last measurements in [LocationState]. An unsupported or misconfigured provider is
 * reported through [LocationState.status] before permission is requested.
 *
 * @param enabled Whether location and orientation updates should run while lifecycle-active.
 * @param provider The [LocationProvider] to use for obtaining location updates and for observing
 *   and requesting foreground location permission. A custom provider whose
 *   [LocationProvider.permission] keeps the granted default needs no permission handling.
 * @param request Preferences for location updates.
 * @param orientationProvider The optional [OrientationProvider] to use for obtaining device
 *   orientation updates. By default, a provider that emits no orientation updates is used, meaning
 *   the orientation in the returned state will always be `null`.
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
  orientationProvider: OrientationProvider = rememberNullOrientationProvider(),
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
                        is LocationEvent.Fix -> state.accept(event)
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
    orientationProvider,
    permission,
    state,
    lifecycleOwner.lifecycle,
    minActiveState,
    coroutineContext,
  ) {
    if (!enabled || permission !is LocationPermission.Granted) return@LaunchedEffect
    lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
      coroutineScope {
        launch {
          val collectOrientation: suspend () -> Unit = {
            orientationProvider.orientation.collect { state.orientation = it }
          }
          if (coroutineContext == EmptyCoroutineContext) collectOrientation()
          else withContext(coroutineContext) { collectOrientation() }
        }
      }
    }
  }

  return state
}

/**
 * Returns the most accurate bearing measurement available.
 *
 * This function considers the bearing from two potential sources:
 * 1. The course from the user's [Location] (derived from GPS or other location services), which
 *    indicates the direction of travel.
 * 2. The orientation from the device's [Orientation] (derived from the compass/magnetometer), which
 *    indicates the direction the top of the device is pointing.
 *
 * It compares the accuracy of these two measurements and returns the one with the smallest accuracy
 * value (i.e., the most precise). If a measurement has no accuracy specified (`null`), it is
 * treated as having infinite (the worst possible) accuracy.
 *
 * @return The [BearingWithAccuracy] with the highest accuracy, or `null` if both the [Location] and
 *   [Orientation] are `null` or do not provide a bearing.
 */
public fun LocationState.mostAccurateBearing(): BearingWithAccuracy? =
  listOfNotNull(location?.course, orientation?.orientation).minByOrNull {
    it.accuracy ?: Double.POSITIVE_INFINITY.degrees
  }
