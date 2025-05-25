package dev.sargunv.maplibrecompose.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.core.CameraMoveReason
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.core.MaplibreMap
import dev.sargunv.maplibrecompose.core.StandardMaplibreMap
import dev.sargunv.maplibrecompose.core.VisibleRegion
import dev.sargunv.maplibrecompose.expressions.ExpressionContext
import dev.sargunv.maplibrecompose.expressions.ast.Expression
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel

/** Remember a new [CameraState] in the initial state as given in [firstPosition]. */
@Composable
public fun rememberCameraState(firstPosition: CameraPosition = CameraPosition()): CameraState =
  rememberSaveable(saver = CameraStateSaver) { CameraState(firstPosition) }

/** Use this class to access information about the map in relation to the camera. */
public class CameraState(firstPosition: CameraPosition) {
  internal var map: MaplibreMap? = null
    set(map) {
      val previousField = field
      field = map

      if (map != null && map !== previousField) {
        (map as StandardMaplibreMap).setCameraPosition(position)
        mapAttachSignal.trySend(map)
      }
    }

  private val mapAttachSignal = Channel<MaplibreMap>()

  internal val positionState = mutableStateOf(firstPosition)
  internal val moveReasonState = mutableStateOf(CameraMoveReason.NONE)
  internal val metersPerDpAtTargetState = mutableStateOf(0.0)
  internal val isCameraMovingState = mutableStateOf(false)

  /** how the camera is oriented towards the map */
  // if the map is not yet initialized, we store the value to apply it later
  public var position: CameraPosition
    get() = positionState.value
    set(value) {
      maybeMap { it.setCameraPosition(value) }
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

  /** suspends until the map has been initialized */
  public suspend fun awaitInitialized() {
    map ?: mapAttachSignal.receive()
  }

  /** Animates the camera towards the [finalPosition] in [duration] time. */
  public suspend fun animateTo(
    finalPosition: CameraPosition,
    duration: Duration = 300.milliseconds,
  ) {
    val map = map ?: mapAttachSignal.receive()
    map.animateCameraPosition(finalPosition, duration)
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
    val map = map ?: mapAttachSignal.receive()
    map.animateCameraPosition(boundingBox, bearing, tilt, padding, duration)
  }

  private fun requireMap(): StandardMaplibreMap {
    check(map != null) {
      "Map requested before it was initialized; try calling awaitInitialization() first"
    }
    return map as? StandardMaplibreMap ?: error("Desktop not supported yet")
  }

  private fun <T> maybeMap(block: (StandardMaplibreMap) -> T): T? {
    return map?.let { block(it as? StandardMaplibreMap ?: error("Desktop not supported yet")) }
  }

  /**
   * Returns an offset from the top-left corner of the map composable that corresponds to the given
   * [position]. This works for positions that are off-screen, too.
   *
   * @throws IllegalStateException if the map is not initialized yet. See [awaitInitialized].
   */
  public fun screenLocationFromPosition(position: Position): DpOffset {
    return requireMap().screenLocationFromPosition(position)
  }

  /**
   * Returns a position that corresponds to the given [offset] from the top-left corner of the map
   * composable.
   *
   * @throws IllegalStateException if the map is not initialized yet. See [awaitInitialized].
   */
  public fun positionFromScreenLocation(offset: DpOffset): Position {
    return requireMap().positionFromScreenLocation(offset)
  }

  /**
   * Returns a list of features that are rendered at the given [offset] from the top-left corner of
   * the map composable, optionally limited to layers with the given [layerIds] and filtered by the
   * given [predicate]. The result is sorted by render order, i.e. the feature in front is first in
   * the list.
   *
   * @param offset position from the top-left corner of the map composable to query for
   * @param layerIds the ids of the layers to limit the query to. If not specified, features in
   *   *any* layer are returned
   * @param predicate expression that has to evaluate to true for a feature to be included in the
   *   result
   */
  public fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature> {
    val predicateOrNull =
      predicate.takeUnless { it == const(true) }?.compile(ExpressionContext.None)
    return maybeMap { it.queryRenderedFeatures(offset, layerIds, predicateOrNull) } ?: emptyList()
  }

  /**
   * Returns a list of features whose rendered geometry intersect with the given [rect], optionally
   * limited to layers with the given [layerIds] and filtered by the given [predicate]. The result
   * is sorted by render order, i.e. the feature in front is first in the list.
   *
   * @param rect rectangle to intersect with rendered geometry
   * @param layerIds the ids of the layers to limit the query to. If not specified, features in
   *   *any* layer are returned
   * @param predicate expression that has to evaluate to true for a feature to be included in the
   *   result
   */
  public fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature> {
    val predicateOrNull =
      predicate.takeUnless { it == const(true) }?.compile(ExpressionContext.None)
    return maybeMap { it.queryRenderedFeatures(rect, layerIds, predicateOrNull) } ?: emptyList()
  }

  /**
   * Returns the smallest bounding box that contains the currently visible area.
   *
   * Note that the bounding box is always a north-aligned rectangle. I.e. if the map is rotated or
   * tilted, the returned bounding box will always be larger than the actually visible area. See
   * [queryVisibleRegion]
   *
   * @throws IllegalStateException if the map is not initialized yet. See [awaitInitialized].
   */
  public fun queryVisibleBoundingBox(): BoundingBox {
    // TODO at some point, this should be refactored to State, just like the camera position
    return requireMap().getVisibleBoundingBox()
  }

  /**
   * Returns the currently visible area, which is a four-sided polygon spanned by the four points
   * each at one corner of the map composable. If the camera has tilt (pitch), this polygon is a
   * trapezoid instead of a rectangle.
   *
   * @throws IllegalStateException if the map is not initialized yet. See [awaitInitialized].
   */
  public fun queryVisibleRegion(): VisibleRegion {
    // TODO at some point, this should be refactored to State, just like the camera position
    return requireMap().getVisibleRegion()
  }

  /**
   * Takes a snapshot of the map with the specified parameters. Functional on Android and iOS only.
   *
   * @param width The width of the snapshot in px.
   * @param height The height of the snapshot in px.
   * @param styleUri The URI of the style to use for the snapshot.
   * @param region The bounding box of the region to capture in the snapshot.
   * @param cameraPosition The camera position to use for the snapshot. The `CameraPosition.target`
   *   is overridden by `region` if set in conjunction.
   * @param showLogo Whether to show the logo in the snapshot. Defaults to true.
   * @return The resulting ImageBitmap of the snapshot.
   * @throws IllegalStateException if the map is not initialized yet. See [awaitInitialized].
   * @throws SnapshotException if an error occurs during snapshot generation.
   */
  public suspend fun snapshot(
    width: Int,
    height: Int,
    styleUri: String = requireMap().getStyleUri(),
    region: BoundingBox? = null,
    cameraPosition: CameraPosition? = null,
    showLogo: Boolean = true,
  ): ImageBitmap =
    requireMap()
      .getMapSnapshotter()
      .snapshot(width, height, styleUri, region, cameraPosition, showLogo)
}
