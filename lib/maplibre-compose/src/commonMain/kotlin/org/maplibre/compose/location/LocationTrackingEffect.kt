package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees

/**
 * A form of [LaunchedEffect] that is specialized for tracking user location.
 *
 * [onLocationChange] will be called, whenever the [location] changes according to the given
 * parameters. Only [Location]s whose position `latitude` or `longitude` changes by at least
 * [precision] compared to the previous location will result in a call to [onLocationChange]. If
 * [trackBearing] is `true`, the bearing must change by at least [precision] as well (or change
 * between null/non-null).
 *
 * If [enabled] is `false` [onLocationChange] will never be called and location is not monitored,
 * i.e. the [LocationProvider] may stop requesting location updates from the platform.
 */
@Composable
public fun LocationTrackingEffect(
  locationState: UserLocationState,
  enabled: Boolean = true,
  trackBearing: Boolean = true,
  precision: Double = 0.00001, // approx 1 m
  onLocationChange: suspend LocationChangeScope.() -> Unit,
) {
  val changeCollector = remember(onLocationChange) { LocationChangeCollector(onLocationChange) }

  LaunchedEffect(enabled, trackBearing, changeCollector) {
    if (!enabled) return@LaunchedEffect

    snapshotFlow { locationState.location }
      .filterNotNull()
      .distinctUntilChanged equal@{ old, new ->
        if (trackBearing && (old.course != null || new.course != null)) {
          if (old.course == null) return@equal false
          if (new.course == null) return@equal false
          if (abs(old.course.value.smallestRotationTo(new.course.value).inDegrees) >= precision)
            return@equal false
        }

        if (abs(old.position.value.latitude - new.position.value.latitude) >= precision)
          return@equal false
        if (abs(old.position.value.longitude - new.position.value.longitude) >= precision)
          return@equal false

        true
      }
      .collect(changeCollector)
  }
}

/**
 * Provides an easy mechanism to keep a map's [org.maplibre.compose.camera.CameraState] in sync with
 * the current location via [LocationTrackingEffect].
 */
public interface LocationChangeScope {
  /** The previous [Location] before the location change */
  public val previousLocation: Location?

  /** The [Location] that caused the location change */
  public val currentLocation: Location

  /**
   * Convenience method for updating a [org.maplibre.compose.camera.CameraState] based on this
   * location change
   *
   * @param animationDuration if `null` updates [org.maplibre.compose.camera.CameraState.position]
   *   directly without animation, otherwise specifies the duration of the camera animation
   * @param updateBearing determines how the bearing affects the camera state
   */
  public suspend fun CameraState.updateFromLocation(
    animationDuration: Duration? = 300.milliseconds,
    updateBearing: BearingUpdate = BearingUpdate.TRACK_AUTOMATIC,
  )
}

public enum class BearingUpdate {
  /** ignore changes in bearing and keep current orientation */
  IGNORE,

  /** ignore changes in bearing and reset orientation to point north */
  ALWAYS_NORTH,

  /** update camera rotation based on location course (direction of movement) */
  TRACK_COURSE,

  /** update camera rotation based on device orientation (heading) */
  // TODO TRACK_ORIENTATION,

  /** update camera rotation based on course if available, otherwise orientation */
  TRACK_AUTOMATIC,

}

internal class LocationChangeCollector(private val onEmit: suspend LocationChangeScope.() -> Unit) :
  FlowCollector<Location>, LocationChangeScope {
  override var previousLocation: Location? = null
  override lateinit var currentLocation: Location

  override suspend fun emit(value: Location) {
    currentLocation = value
    onEmit()
    previousLocation = value
  }

  override suspend fun CameraState.updateFromLocation(
    animationDuration: Duration?,
    updateBearing: BearingUpdate,
  ) {
    val cameraState = this

    val newPosition =
      when (updateBearing) {
        BearingUpdate.IGNORE -> {
          cameraState.position.copy(target = currentLocation.position.value)
        }

        BearingUpdate.ALWAYS_NORTH -> {
          cameraState.position.copy(target = currentLocation.position.value, bearing = 0.0)
        }

        BearingUpdate.TRACK_COURSE -> {
          cameraState.position.copy(
            target = currentLocation.position.value,
            bearing =
              currentLocation.course?.value?.clockwiseRotationTo(Bearing.North)?.inDegrees
                ?: cameraState.position.bearing,
          )
        }

        BearingUpdate.TRACK_AUTOMATIC -> {
          cameraState.position.copy(
            target = currentLocation.position.value,
            bearing =
              currentLocation.course?.value?.smallestRotationTo(Bearing.North)?.inDegrees
                ?: cameraState.position.bearing,
          )
        }
      }

    if (animationDuration != null) {
      cameraState.animateTo(newPosition, animationDuration)
    } else {
      cameraState.position = newPosition
    }
  }
}
