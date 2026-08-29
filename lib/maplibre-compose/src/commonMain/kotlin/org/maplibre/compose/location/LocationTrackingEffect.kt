package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * A form of [LaunchedEffect] that is specialized for tracking user location.
 *
 * [onLocationChange] is called when [LocationState.lastReading] changes. Course or device-heading
 * changes also trigger it when [trackBearing] is `true`.
 *
 * If [enabled] is `false`, [onLocationChange] is never called. Disabling this effect stops
 * observation but does not control [LocationState]'s platform location request. Pass the same
 * enabled state to [rememberLocationState] when those lifetimes should match.
 *
 * @param locationState State to observe.
 * @param enabled Whether callbacks are enabled.
 * @param trackBearing Whether course or device-heading changes can trigger a callback.
 * @param onLocationChange Callback with the previous and current measurements.
 */
@Composable
public fun LocationTrackingEffect(
  locationState: LocationState,
  enabled: Boolean = true,
  trackBearing: Boolean = true,
  onLocationChange: suspend LocationChangeScope.() -> Unit,
) {
  val changeCollector = remember(onLocationChange) { LocationChangeCollector(onLocationChange) }

  LaunchedEffect(locationState, enabled, trackBearing, changeCollector) {
    if (!enabled) return@LaunchedEffect

    // Read both mutable properties inside snapshotFlow; observing LocationState itself would not
    // emit when either property changes.
    snapshotFlow {
        locationState.lastReading?.let { LocationSnapshot(it, locationState.lastHeading) }
      }
      .filterNotNull()
      .distinctUntilChanged { old, new ->
        if (trackBearing) old == new
        else
          old.location.copy(course = null, courseAccuracy = null) ==
            new.location.copy(course = null, courseAccuracy = null)
      }
      .collect(changeCollector)
  }
}

/** The measurements that triggered a [LocationTrackingEffect] callback. */
public interface LocationChangeScope {
  /** The location reading from the previous callback, or `null` for the first callback. */
  public val previousReading: LocationReading?

  /** The location reading that triggered this callback. */
  public val currentReading: LocationReading

  /** The most recently received device heading. */
  public val currentHeading: Heading?
}

private data class LocationSnapshot(val location: LocationReading, val heading: Heading?)

private class LocationChangeCollector(private val onEmit: suspend LocationChangeScope.() -> Unit) :
  FlowCollector<LocationSnapshot>, LocationChangeScope {
  private var previousSnapshot: LocationSnapshot? = null
  private lateinit var currentSnapshot: LocationSnapshot

  override val previousReading: LocationReading?
    get() = previousSnapshot?.location

  override val currentReading: LocationReading
    get() = currentSnapshot.location

  override val currentHeading: Heading?
    get() = currentSnapshot.heading

  override suspend fun emit(value: LocationSnapshot) {
    currentSnapshot = value
    onEmit()
    previousSnapshot = value
  }
}
