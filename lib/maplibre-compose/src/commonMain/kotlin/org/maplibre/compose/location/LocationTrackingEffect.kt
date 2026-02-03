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
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees

/**
 * A form of [LaunchedEffect] that is specialized for tracking user location.
 *
 * [onLocationChange] will be called, whenever the [UserLocationState] changes according to the
 * given parameters. Only [Location]s whose position `latitude` or `longitude` changes by at least
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

    snapshotFlow { locationState }
      .distinctUntilChanged equal@{ old, new ->
        val oldLocation = old.location
        val newLocation = new.location

        if (oldLocation != null && newLocation != null) {
          if (trackBearing && (oldLocation.course != null || newLocation.course != null)) {
            if (oldLocation.course == null) return@equal false
            if (newLocation.course == null) return@equal false
            if (
              abs(
                oldLocation.course.value.smallestRotationTo(newLocation.course.value).inDegrees
              ) >= precision
            )
              return@equal false
          }

          if (
            abs(oldLocation.position.value.latitude - newLocation.position.value.latitude) >=
              precision
          )
            return@equal false
          if (
            abs(oldLocation.position.value.longitude - newLocation.position.value.longitude) >=
              precision
          )
            return@equal false
        }

        val oldOrientation = old.orientation?.orientation
        val newOrientation = new.orientation?.orientation

        if (trackBearing && oldOrientation != null && newOrientation != null) {
          if (oldOrientation.value.smallestRotationTo(newOrientation.value).inDegrees >= precision)
            return@equal false
        }

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
  /** The previous [UserLocationState] before the location change */
  public val previousLocation: UserLocationState?

  /** The [UserLocationState] that caused the location change */
  public val currentLocation: UserLocationState

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
  TRACK_ORIENTATION,

  /**
   * Updates the camera's bearing based on the more accurate of two sources: course (direction of
   * movement) or orientation (device heading).
   */
  TRACK_AUTOMATIC,
}

internal class LocationChangeCollector(private val onEmit: suspend LocationChangeScope.() -> Unit) :
  FlowCollector<UserLocationState>, LocationChangeScope {
  override var previousLocation: UserLocationState? = null
  override lateinit var currentLocation: UserLocationState

  override suspend fun emit(value: UserLocationState) {
    currentLocation = value
    onEmit()
    previousLocation = value
  }

  override suspend fun CameraState.updateFromLocation(
    animationDuration: Duration?,
    updateBearing: BearingUpdate,
  ) {
    val cameraState = this

    val target = currentLocation.location?.position?.value ?: cameraState.position.target

    val newPosition =
      when (updateBearing) {
        BearingUpdate.IGNORE -> {
          cameraState.position.copy(target = target)
        }

        BearingUpdate.ALWAYS_NORTH -> {
          cameraState.position.copy(target = target, bearing = 0.0)
        }

        BearingUpdate.TRACK_COURSE -> {
          cameraState.position.copy(
            target = target,
            bearing =
              currentLocation.location?.course?.value?.clockwiseRotationTo(Bearing.North)?.inDegrees
                ?: cameraState.position.bearing,
          )
        }

        BearingUpdate.TRACK_ORIENTATION -> {
          cameraState.position.copy(
            target = target,
            bearing =
              currentLocation.orientation
                ?.orientation
                ?.value
                ?.clockwiseRotationTo(Bearing.North)
                ?.inDegrees ?: cameraState.position.bearing,
          )
        }

        BearingUpdate.TRACK_AUTOMATIC -> {
          cameraState.position.copy(
            target = target,
            bearing =
              currentLocation
                .minAccuracyBearing()
                ?.value
                ?.clockwiseRotationTo(Bearing.North)
                ?.inDegrees ?: cameraState.position.bearing,
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
