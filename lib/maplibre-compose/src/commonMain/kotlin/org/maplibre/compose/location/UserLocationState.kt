package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.units.extensions.degrees

public class UserLocationState
internal constructor(locationState: State<Location?>, orientationState: State<Orientation?>) {
  /** The user's current or last known location */
  public val location: Location? by locationState

  /** The device's current or last known orientation */
  public val orientation: Orientation? by orientationState
}

/**
 * Remembers and returns a [UserLocationState] that can be used to track the user's location and
 * device orientation.
 *
 * To prevent excessive recompositions from rapid location or orientation updates, the data is
 * sampled at a regular interval defined by [samplePeriod].
 *
 * @param locationProvider The [LocationProvider] to use for obtaining location updates.
 * @param orientationProvider The optional [OrientationProvider] to use for obtaining device
 *   orientation updates. By default, a provider that emits no orientation updates is used, meaning
 *   the orientation in the returned state will always be `null`.
 * @param samplePeriod The sampling period for collecting location and orientation updates. This is
 *   useful for throttling updates to prevent excessive recompositions. Defaults to 1 second.
 * @param lifecycleOwner The [LifecycleOwner] to scope the collection of updates to. Defaults to the
 *   current [LocalLifecycleOwner].
 * @param minActiveState The minimum [Lifecycle.State] at which to collect updates. Defaults to
 *   [Lifecycle.State.STARTED].
 * @param coroutineContext The [CoroutineContext] to use for collecting updates. Defaults to
 *   [EmptyCoroutineContext].
 * @return A remembered [UserLocationState] instance.
 */
@OptIn(FlowPreview::class)
@Composable
public fun rememberUserLocationState(
  locationProvider: LocationProvider,
  orientationProvider: OrientationProvider = rememberNullOrientationProvider(),
  samplePeriod: Duration = 1.seconds,
  lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
  minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
  coroutineContext: CoroutineContext = EmptyCoroutineContext,
): UserLocationState {
  val locationState = remember { mutableStateOf<Location?>(null) }
  val orientationState = remember { mutableStateOf<Orientation?>(null) }
  val state = remember { UserLocationState(locationState, orientationState) }

  LaunchedEffect(
    locationProvider,
    orientationProvider,
    lifecycleOwner.lifecycle,
    minActiveState,
    coroutineContext,
  ) {
    suspend fun collect() {
      var location: Location? = null
      var orientation: Orientation? = null
      locationProvider.location.onEach { location = it }.launchIn(this)
      orientationProvider.orientation.onEach { orientation = it }.launchIn(this)

      while (isActive) {
        locationState.value = location
        orientationState.value = orientation
        delay(samplePeriod)
      }
    }

    lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
      if (coroutineContext == EmptyCoroutineContext) {
        collect()
      } else {
        withContext(coroutineContext) { collect() }
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
 * @return The [BearingWithAccuracy] with the highest accuracy, or `null` if both [Location] and
 *   [Orientation] are `null` or do not provide a bearing.
 */
public fun UserLocationState.mostAccurateBearing(): BearingWithAccuracy? {
  return listOfNotNull(location?.course, orientation?.orientation).minByOrNull {
    it.accuracy ?: Double.POSITIVE_INFINITY.degrees
  }
}
