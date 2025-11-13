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
  location: Location?,
  enabled: Boolean = true,
  trackBearing: Boolean = true,
  precision: Double = 0.00001, // approx 1 m
  onLocationChange: suspend LocationChangeScope.() -> Unit,
) {
  val changeCollector = remember(onLocationChange) { LocationChangeCollector(onLocationChange) }

  LaunchedEffect(enabled, trackBearing, changeCollector) {
    if (!enabled) return@LaunchedEffect

    snapshotFlow { location }
      .filterNotNull()
      .distinctUntilChanged equal@{ old, new ->
        // Check bearing changes if tracking is enabled
        if (trackBearing) {
          val oldBearing = old.course?.bearing ?: old.orientation?.bearing
          val newBearing = new.course?.bearing ?: new.orientation?.bearing

          if ((oldBearing != null) || (newBearing != null)) {
            if (oldBearing == null) return@equal false
            if (newBearing == null) return@equal false
            if (
              abs(
                oldBearing.clockwiseRotationTo(Bearing.North).inDegrees -
                  newBearing.clockwiseRotationTo(Bearing.North).inDegrees
              ) >= precision
            )
              return@equal false
          }
        }

        // Check position changes
        val oldPos = old.position?.position
        val newPos = new.position?.position

        if (oldPos == null || newPos == null) return@equal oldPos == newPos

        if (abs(oldPos.latitude - newPos.latitude) >= precision) return@equal false
        if (abs(oldPos.longitude - newPos.longitude) >= precision) return@equal false

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
    updateBearing: BearingUpdate = BearingUpdate.TRACK_LOCATION,
  )
}

public enum class BearingUpdate {
  /** ignore changes in bearing and keep current orientation */
  IGNORE,

  /** ignore changes in bearing and reset orientation to point north */
  ALWAYS_NORTH,

  /** update camera orientation based on location course (direction of movement) */
  TRACK_COURSE,

  /** update camera orientation based on device orientation (heading) */
  TRACK_ORIENTATION,

  /** update camera orientation based on course if available, otherwise orientation */
  TRACK_LOCATION,
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
    val position = currentLocation.position?.position

    if (position == null) return

    val newPosition =
      when (updateBearing) {
        BearingUpdate.IGNORE -> {
          cameraState.position.copy(target = position)
        }

        BearingUpdate.ALWAYS_NORTH -> {
          cameraState.position.copy(target = position, bearing = 0.0)
        }

        BearingUpdate.TRACK_COURSE -> {
          cameraState.position.copy(
            target = position,
            bearing =
              currentLocation.course?.bearing?.clockwiseRotationTo(Bearing.North)?.inDegrees
                ?: cameraState.position.bearing,
          )
        }

        BearingUpdate.TRACK_ORIENTATION -> {
          cameraState.position.copy(
            target = position,
            bearing =
              currentLocation.orientation?.bearing?.clockwiseRotationTo(Bearing.North)?.inDegrees
                ?: cameraState.position.bearing,
          )
        }

        BearingUpdate.TRACK_LOCATION -> {
          val bearing = currentLocation.course?.bearing ?: currentLocation.orientation?.bearing
          cameraState.position.copy(
            target = position,
            bearing =
              bearing?.clockwiseRotationTo(Bearing.North)?.inDegrees ?: cameraState.position.bearing,
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
