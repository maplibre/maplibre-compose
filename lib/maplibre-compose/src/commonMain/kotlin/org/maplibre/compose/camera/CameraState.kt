package org.maplibre.compose.camera

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.map.MapAdapter
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** Remember a new [CameraState] in the initial state as given in [firstPosition]. */
@Composable
public fun rememberCameraState(firstPosition: CameraPosition = CameraPosition()): CameraState =
  rememberSaveable(saver = CameraStateSaver) { CameraState(firstPosition) }

/** Use this class to access information about the map in relation to the camera. */
public class CameraState(firstPosition: CameraPosition) {
  internal val mapState = mutableStateOf<MapAdapter?>(null)
  internal val viewportState = mutableStateOf<Viewport?>(null)
  internal val positionState = mutableStateOf(firstPosition)
  internal val moveReasonState = mutableStateOf(CameraMoveReason.NONE)
  internal val isCameraMovingState = mutableStateOf(false)

  internal var map: MapAdapter?
    get() = mapState.value
    set(map) {
      val prevMap = mapState.value
      mapState.value = map

      if (map !== prevMap && map is MapAdapter) {
        // apply deferred state
        map.setCameraPosition(position)

        // usually null until the map reports its first viewport
        viewportState.value = map.getViewport()
      } else if (map == null) {
        // a snapshot kept past detachment would report a viewport no map is showing
        viewportState.value = null
      }
    }

  /** The density of the map composable this state is attached to, or null before composition. */
  internal var density: Density? = null

  /**
   * What the map shows right now: the size of the map composable and the visible area. Null until
   * the map has rendered its first viewport. A composition that reads this property recomposes
   * after a camera move or a resize of the map composable, because the instance is replaced once
   * the map has adopted either change.
   */
  public val viewport: Viewport?
    get() = viewportState.value

  /**
   * Returns the offset from the top-left corner of the map composable that corresponds to the given
   * [position], or null while the map has no [viewport]. This works for positions that are
   * off-screen, too.
   *
   * The answer describes the transform that the map has at the time of the call. To recompose when
   * the transform changes, read [viewport].
   */
  public fun screenLocationFromPosition(position: Position): DpOffset? {
    return map?.screenLocationFromPosition(position)
  }

  /**
   * Returns the position that corresponds to the given [offset] from the top-left corner of the map
   * composable, or null while the map has no [viewport].
   *
   * The answer describes the transform that the map has at the time of the call. To recompose when
   * the transform changes, read [viewport].
   */
  public fun positionFromScreenLocation(offset: DpOffset): Position? {
    return map?.positionFromScreenLocation(offset)
  }

  /**
   * Returns the position that corresponds to the given [offset] in pixels from the top-left corner
   * of the map composable, in the units that pointer events report, or null while the map has no
   * [viewport].
   *
   * The answer describes the transform that the map has at the time of the call. To recompose when
   * the transform changes, read [viewport].
   */
  public fun positionFromScreenLocation(offset: Offset): Position? {
    val density = density ?: return null
    return positionFromScreenLocation(with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) })
  }

  /**
   * Returns a list of features that are rendered at the given [offset] from the top-left corner of
   * the map composable, optionally limited to layers with the given [layerIds] and filtered by the
   * given [predicate]. The result is sorted by render order, i.e. the feature in front is first in
   * the list. The list is empty while this state is not attached to a map.
   *
   * @param offset position from the top-left corner of the map composable to query for
   * @param layerIds the ids of the layers to limit the query to. If not specified, features in
   *   *any* layer are returned
   * @param predicate expression that has to evaluate to true for a feature to be included in the
   *   result
   */
  public suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> {
    return map?.queryRenderedFeatures(offset, layerIds, predicate.compileOrNull()) ?: emptyList()
  }

  /**
   * Returns a list of features whose rendered geometry intersect with the given [rect], optionally
   * limited to layers with the given [layerIds] and filtered by the given [predicate]. The result
   * is sorted by render order, i.e. the feature in front is first in the list. The list is empty
   * while this state is not attached to a map.
   *
   * @param rect rectangle to intersect with rendered geometry
   * @param layerIds the ids of the layers to limit the query to. If not specified, features in
   *   *any* layer are returned
   * @param predicate expression that has to evaluate to true for a feature to be included in the
   *   result
   */
  public suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> {
    return map?.queryRenderedFeatures(rect, layerIds, predicate.compileOrNull()) ?: emptyList()
  }

  private fun Expression<BooleanValue>.compileOrNull(): CompiledExpression<BooleanValue>? =
    takeUnless {
      it == const(true)
    }
    ?.compile(ExpressionContext.None)

  /** how the camera is oriented towards the map */
  // if the map is not yet initialized, we store the value to apply it later
  public var position: CameraPosition
    get() = positionState.value
    set(value) {
      map?.setCameraPosition(value)
      positionState.value = value
    }

  /** reason why the camera moved, last time it moved */
  public val moveReason: CameraMoveReason
    get() = moveReasonState.value

  /** whether the camera is currently moving */
  public val isCameraMoving: Boolean
    get() = isCameraMovingState.value

  internal suspend fun awaitMap(): MapAdapter {
    return snapshotFlow { map }.first { it != null }!!
  }

  /** Suspends until the map this state is attached to has rendered its first viewport. */
  public suspend fun awaitViewport(): Viewport {
    return snapshotFlow { viewport }.first { it != null }!!
  }

  /** Animates the camera towards the [finalPosition] in [duration] time. */
  public suspend fun animateTo(
    finalPosition: CameraPosition,
    duration: Duration = 300.milliseconds,
  ) {
    awaitMap().animateCameraPosition(finalPosition, duration)
  }

  /** Immediately moves the camera to [finalPosition]. */
  public suspend fun jumpTo(finalPosition: CameraPosition) {
    awaitMap().setCameraPosition(finalPosition)
  }

  /**
   * Animates the camera towards the specified [boundingBox] in the given [duration] time with the
   * specified [bearing], [tilt], and [padding].
   *
   * @param boundingBox The bounds to animate the camera to.
   * @param bearing The bearing to set during the animation. Defaults to 0.0.
   * @param tilt The tilt to set during the animation. Defaults to 0.0.
   * @param padding Additional space between the bounds and the camera viewport. The `cameraPadding`
   *   configured on [org.maplibre.compose.map.MaplibreMap] remains active after the animation.
   * @param duration The duration of the animation. Defaults to 300 ms.
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

  /**
   * Immediately moves the camera to the specified [boundingBox] with the specified [bearing],
   * [tilt], and [padding].
   *
   * @param boundingBox The bounds to animate the camera to.
   * @param bearing The bearing to set during the animation. Defaults to 0.0.
   * @param tilt The tilt to set during the animation. Defaults to 0.0.
   * @param padding Additional space between the bounds and the camera viewport. The `cameraPadding`
   *   configured on [org.maplibre.compose.map.MaplibreMap] remains active after the move.
   */
  public suspend fun jumpTo(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
  ) {
    awaitMap().setCameraPosition(boundingBox, bearing, tilt, padding)
  }
}
