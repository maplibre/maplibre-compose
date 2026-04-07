package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable

/** Determines how the map camera should track a native user location source. */
public enum class UserTrackingMode {
  None,
  Follow,
  FollowWithCourse,
}

/** Determines whether the native platform puck should be shown. */
public enum class NativeLocationPuck {
  None,
  Default,
}

/**
 * State holder for native user-tracking mode.
 *
 * This mirrors the current desired tracking mode and will later also reflect native dismissals.
 */
public class NativeLocationTrackingState internal constructor(initialMode: UserTrackingMode) {
  internal val trackingModeState = mutableStateOf(initialMode)

  public var trackingMode: UserTrackingMode
    get() = trackingModeState.value
    set(value) {
      trackingModeState.value = value
    }

  internal fun setFromMap(value: UserTrackingMode) {
    trackingModeState.value = value
  }
}

/** Remember a [NativeLocationTrackingState] in the given initial mode. */
@Composable
public fun rememberNativeLocationTrackingState(
  initialMode: UserTrackingMode = UserTrackingMode.None
): NativeLocationTrackingState =
  rememberSaveable(saver = NativeLocationTrackingStateSaver) {
    NativeLocationTrackingState(initialMode)
  }

/**
 * Configures an app-provided native location source for the map.
 *
 * The provided [locationState] drives the native puck and native tracking pipeline where supported.
 */
public data class NativeLocationTracking(
  val locationState: UserLocationState,
  val state: NativeLocationTrackingState,
  val puck: NativeLocationPuck = NativeLocationPuck.Default,
)
