package org.maplibre.compose.location

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Rotation
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inDegrees

/**
 * Convenience method for keeping [camera] in sync with the location change that triggered this
 * [LocationTrackingEffect] callback.
 *
 * @param animationDuration if `null`, updates [CameraState.position] directly without animation;
 *   otherwise, specifies the duration of the camera animation.
 * @param updateBearing determines how the bearing affects the camera state.
 */
public suspend fun LocationChangeScope.updateCamera(
  camera: CameraState,
  animationDuration: Duration? = 300.milliseconds,
  updateBearing: BearingUpdate = BearingUpdate.TRACK_AUTOMATIC,
) {
  val selectedBearing =
    when (updateBearing) {
      BearingUpdate.IGNORE -> null
      BearingUpdate.ALWAYS_NORTH -> Bearing.North
      BearingUpdate.TRACK_COURSE -> currentReading.course
      BearingUpdate.TRACK_HEADING -> currentHeading?.bearing
      BearingUpdate.TRACK_AUTOMATIC -> mostAccurateBearing()
    }

  val newPosition =
    camera.position.copy(
      target = currentReading.position,
      bearing =
        when (updateBearing) {
          BearingUpdate.IGNORE -> camera.position.bearing
          else -> selectedBearing?.let { (it - Bearing.North).inDegrees } ?: camera.position.bearing
        },
    )

  if (animationDuration == null) camera.position = newPosition
  else camera.animateTo(newPosition, animationDuration)
}

/** How [updateCamera] updates camera bearing. */
public enum class BearingUpdate {
  /** Ignore changes in bearing and keep the current orientation. */
  IGNORE,

  /** Ignore changes in bearing and reset the orientation to point north. */
  ALWAYS_NORTH,

  /** Update camera rotation based on location course (direction of movement). */
  TRACK_COURSE,

  /** Update camera rotation based on the device heading. */
  TRACK_HEADING,

  /**
   * Update the camera's bearing based on the more accurate of two sources: course (direction of
   * movement) or heading (the direction that the device faces).
   */
  TRACK_AUTOMATIC,
}

private fun LocationChangeScope.mostAccurateBearing(): Bearing? =
  listOfNotNull(
      currentReading.course?.let { CameraBearingMeasurement(it, currentReading.courseAccuracy) },
      currentHeading?.let { CameraBearingMeasurement(it.bearing, it.accuracy) },
    )
    .minByOrNull { it.accuracy ?: Double.POSITIVE_INFINITY.degrees }
    ?.bearing

private data class CameraBearingMeasurement(val bearing: Bearing, val accuracy: Rotation?)
