package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inDegrees

/**
 * A form of [LaunchedEffect] that is specialized for tracking user location.
 *
 * [onLocationChange] is called when [LocationState.location] changes. Course or device-orientation
 * changes also trigger it when [trackBearing] is `true`.
 *
 * If [enabled] is `false`, [onLocationChange] is never called. Disabling this effect stops
 * observation but does not control [LocationState]'s platform session; pass the same enabled state
 * to [rememberLocationState] when those lifetimes should match.
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
        locationState.location?.let { LocationSnapshot(it, locationState.orientation) }
      }
      .filterNotNull()
      .distinctUntilChanged { old, new ->
        if (trackBearing) old == new
        else old.location.copy(course = null) == new.location.copy(course = null)
      }
      .collect(changeCollector)
  }
}

/**
 * Provides an easy mechanism to keep a map's [CameraState] in sync with the current location via
 * [LocationTrackingEffect].
 */
public interface LocationChangeScope {
  /** The location from the previous callback, or `null` for the first callback. */
  public val previousLocation: Location?

  /** The location that triggered this callback. */
  public val currentLocation: Location

  /** The most recently received device orientation. */
  public val currentOrientation: Orientation?

  /**
   * Convenience method for updating a [CameraState] based on this location change.
   *
   * @param animationDuration if `null`, updates [CameraState.position] directly without animation;
   *   otherwise, specifies the duration of the camera animation.
   * @param updateBearing determines how the bearing affects the camera state.
   */
  public suspend fun CameraState.updateFromLocation(
    animationDuration: Duration? = 300.milliseconds,
    updateBearing: BearingUpdate = BearingUpdate.TRACK_AUTOMATIC,
  )
}

/** How [LocationChangeScope.updateFromLocation] updates camera bearing. */
public enum class BearingUpdate {
  /** Ignore changes in bearing and keep the current orientation. */
  IGNORE,

  /** Ignore changes in bearing and reset the orientation to point north. */
  ALWAYS_NORTH,

  /** Update camera rotation based on location course (direction of movement). */
  TRACK_COURSE,

  /** Update camera rotation based on device orientation (heading). */
  TRACK_ORIENTATION,

  /**
   * Update the camera's bearing based on the more accurate of two sources: course (direction of
   * movement) or orientation (device heading).
   */
  TRACK_AUTOMATIC,
}

private data class LocationSnapshot(val location: Location, val orientation: Orientation?)

private class LocationChangeCollector(private val onEmit: suspend LocationChangeScope.() -> Unit) :
  FlowCollector<LocationSnapshot>, LocationChangeScope {
  private var previousSnapshot: LocationSnapshot? = null
  private lateinit var currentSnapshot: LocationSnapshot

  override val previousLocation: Location?
    get() = previousSnapshot?.location

  override val currentLocation: Location
    get() = currentSnapshot.location

  override val currentOrientation: Orientation?
    get() = currentSnapshot.orientation

  override suspend fun emit(value: LocationSnapshot) {
    currentSnapshot = value
    onEmit()
    previousSnapshot = value
  }

  override suspend fun CameraState.updateFromLocation(
    animationDuration: Duration?,
    updateBearing: BearingUpdate,
  ) {
    val selectedBearing =
      when (updateBearing) {
        BearingUpdate.IGNORE -> null
        BearingUpdate.ALWAYS_NORTH -> Bearing.North
        BearingUpdate.TRACK_COURSE -> currentLocation.course?.value
        BearingUpdate.TRACK_ORIENTATION -> currentOrientation?.orientation?.value
        BearingUpdate.TRACK_AUTOMATIC -> mostAccurateBearing(currentSnapshot)
      }

    val newPosition =
      position.copy(
        target = currentLocation.position.value,
        bearing =
          when (updateBearing) {
            BearingUpdate.IGNORE -> position.bearing
            else -> selectedBearing?.let { (it - Bearing.North).inDegrees } ?: position.bearing
          },
      )

    if (animationDuration == null) position = newPosition
    else animateTo(newPosition, animationDuration)
  }
}

private fun mostAccurateBearing(snapshot: LocationSnapshot): Bearing? =
  listOfNotNull(snapshot.location.course, snapshot.orientation?.orientation)
    .minByOrNull { it.accuracy ?: Double.POSITIVE_INFINITY.degrees }
    ?.value
