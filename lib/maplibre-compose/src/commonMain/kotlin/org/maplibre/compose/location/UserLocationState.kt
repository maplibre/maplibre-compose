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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

public class UserLocationState
internal constructor(locationState: State<Location?>, orientationState: State<Orientation?>) {
  /** The user's current or last known location */
  public val location: Location? by locationState

  /** The device's current or last known orientation */
  public val orientation: Orientation? by orientationState
}

/**
 * Remembers a [UserLocationState] that can be used to track the user's location and device
 * orientation.
 *
 * @param locationProvider The [LocationProvider] to use for obtaining location updates.
 * @param orientationProvider The optional [OrientationProvider] to use for obtaining device
 *   orientation updates. By default, the orientation in the returned state will always be `null`.
 * @param samplePeriod The duration to sample the combined location and orientation flow. If `null`,
 *   all updates are collected. Defaults to 1 second. This is useful for throttling updates to
 *   prevent excessive recompositions.
 * @param lifecycleOwner The [LifecycleOwner] to scope the collection of updates to. Defaults to the
 *   [LocalLifecycleOwner].
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
      while (isActive) {
        locationState.value = locationProvider.location.first()
        orientationState.value = orientationProvider.orientation.first()
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
