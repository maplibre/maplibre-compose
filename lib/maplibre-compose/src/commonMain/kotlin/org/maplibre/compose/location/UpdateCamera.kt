package org.maplibre.compose.location

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inDegrees

/**
 * Convenience method for keeping the camera of [map] in sync with the location change that
 * triggered this [LocationTrackingEffect] callback.
 *
 * @param animationDuration if `null`, sets the camera directly without animation; otherwise,
 *   specifies the duration of the camera animation.
 * @param updateBearing determines how the bearing affects the camera.
 */
public suspend fun LocationChangeScope.updateCamera(
  map: MapState,
  animationDuration: Duration? = 300.milliseconds,
  updateBearing: BearingUpdate = BearingUpdate.TRACK_AUTOMATIC,
) {
  val selectedBearing =
    when (updateBearing) {
      BearingUpdate.IGNORE -> null
      BearingUpdate.ALWAYS_NORTH -> Bearing.North
      BearingUpdate.TRACK_COURSE -> currentLocation.course?.value
      BearingUpdate.TRACK_ORIENTATION -> currentOrientation?.orientation?.value
      BearingUpdate.TRACK_AUTOMATIC -> mostAccurateBearing()
    }

  val newPosition =
    map.camera.copy(
      target = currentLocation.position.value,
      bearing =
        when (updateBearing) {
          BearingUpdate.IGNORE -> map.camera.bearing
          else -> selectedBearing?.let { (it - Bearing.North).inDegrees } ?: map.camera.bearing
        },
    )

  if (animationDuration == null) map.setCamera(newPosition)
  else map.animateCamera(newPosition, animationDuration)
}

/** How [updateCamera] updates camera bearing. */
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

private fun LocationChangeScope.mostAccurateBearing(): Bearing? =
  listOfNotNull(currentLocation.course, currentOrientation?.orientation)
    .minByOrNull { it.accuracy ?: Double.POSITIVE_INFINITY.degrees }
    ?.value
