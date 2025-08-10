package org.maplibre.compose.camera

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import io.github.dellisd.spatialk.geojson.BoundingBox
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import org.maplibre.compose.map.MapAdapter

/** Remember a new [CameraState] in the initial state as given in [firstPosition]. */
@Composable
public fun rememberCameraState(firstPosition: CameraPosition = CameraPosition()): CameraState =
  rememberSaveable(saver = CameraStateSaver) { CameraState(firstPosition) }

/** Use this class to access information about the map in relation to the camera. */
public class CameraState(firstPosition: CameraPosition) {
  internal val mapState = mutableStateOf<MapAdapter?>(null)
  internal val projectionState = mutableStateOf<CameraProjection?>(null)
  internal val positionState = mutableStateOf(firstPosition)
  internal val moveReasonState = mutableStateOf(CameraMoveReason.NONE)
  internal val metersPerDpAtTargetState = mutableDoubleStateOf(0.0)
  internal val isCameraMovingState = mutableStateOf(false)

  internal var map: MapAdapter?
    get() = mapState.value
    set(map) {
      val prevMap = mapState.value
      mapState.value = map

      if (map !== prevMap && map is MapAdapter) {
        // apply deferred state
        map.setCameraPosition(position)

        // initialize imperative API
        projectionState.value = CameraProjection(map)
      }
    }

  /** null until the CameraState has been attached to a map */
  public val projection: CameraProjection?
    get() = projectionState.value

  /** how the camera is oriented towards the map */
  // if the map is not yet initialized, we store the value to apply it later
  public var position: CameraPosition
    get() = positionState.value
    set(value) {
      (map as? MapAdapter)?.setCameraPosition(value)
      positionState.value = value
    }

  /** reason why the camera moved, last time it moved */
  public val moveReason: CameraMoveReason
    get() = moveReasonState.value

  /** meters per dp at the target position. Zero when the map is not initialized yet. */
  public val metersPerDpAtTarget: Double
    get() = metersPerDpAtTargetState.value

  /** whether the camera is currently moving */
  public val isCameraMoving: Boolean
    get() = isCameraMovingState.value

  internal suspend fun awaitMap(): MapAdapter {
    return snapshotFlow { map }.first { it != null }!!
  }

  /** Suspends until the CameraState has been attached to the map. */
  public suspend fun awaitProjection(): CameraProjection {
    return snapshotFlow { projection }.first { it != null }!!
  }

  /** Animates the camera towards the [finalPosition] in [duration] time. */
  public suspend fun animateTo(
    finalPosition: CameraPosition,
    duration: Duration = 300.milliseconds,
  ) {
    awaitMap().animateCameraPosition(finalPosition, duration)
  }

  /**
   * Animates the camera towards the specified [boundingBox] in the given [duration] time with the
   * specified [bearing], [tilt], and [padding].
   *
   * @param boundingBox The bounds to animate the camera to.
   * @param bearing The bearing to set during the animation. Defaults to 0.0.
   * @param tilt The tilt to set during the animation. Defaults to 0.0.
   * @param padding The padding to apply during the animation. Defaults to no padding.
   * @param duration The duration of the animation. Defaults to 300 ms. Has no effect on JS.
   */
  public suspend fun animateTo(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
    duration: Duration = 300.milliseconds,
  ) {
    awaitMap().animateCameraPosition(boundingBox, bearing, tilt, padding, duration)
  }
}
