package org.maplibre.compose.location

import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees

/**
 * Convenience method for keeping [mapState] in sync with the location change that triggered this
 * [LocationTrackingEffect] callback.
 *
 * @param animation the camera transition to the new location, or `null` to move the camera without
 *   one.
 * @param updateBearing determines how the bearing affects the camera state.
 */
public suspend fun LocationChangeScope.updateCamera(
  mapState: MapState,
  animation: CameraAnimation? = CameraAnimation.Ease(),
  updateBearing: BearingUpdate = BearingUpdate.TRACK_AUTOMATIC,
) {
  val selectedBearing =
    when (updateBearing) {
      BearingUpdate.IGNORE -> null
      BearingUpdate.ALWAYS_NORTH -> Bearing.North
      BearingUpdate.TRACK_COURSE -> currentLocation.course
      BearingUpdate.TRACK_HEADING -> currentHeading?.bearing
      BearingUpdate.TRACK_AUTOMATIC -> mostAccurateBearing()
    }

  val newPosition =
    mapState.cameraPosition.copy(
      target = currentLocation.position,
      bearing =
        when (updateBearing) {
          BearingUpdate.IGNORE -> mapState.cameraPosition.bearing
          else ->
            selectedBearing?.let { (it - Bearing.North).inDegrees }
              ?: mapState.cameraPosition.bearing
        },
    )

  if (animation == null) mapState.setCameraPosition(newPosition)
  else mapState.animateCameraPosition(newPosition, animation)
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
  selectMostAccurateBearing(
      currentLocation.course?.let { BearingMeasurement(it, currentLocation.courseAccuracy) },
      currentHeading?.let { BearingMeasurement(it.bearing, it.accuracy) },
    )
    ?.bearing
