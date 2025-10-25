package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

public class UserLocationState internal constructor(locationState: State<Location?>) {
  /** The user's current or last known location */
  public val location: Location? by locationState
}

@Composable
public fun rememberUserLocationState(
  locationProvider: LocationProvider,
  lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
  minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
  coroutineContext: CoroutineContext = EmptyCoroutineContext,
): UserLocationState {
  val locationState =
    locationProvider.location.collectAsStateWithLifecycle(
      lifecycleOwner = lifecycleOwner,
      minActiveState = minActiveState,
      context = coroutineContext,
    )
  return remember(locationState) { UserLocationState(locationState) }
}
