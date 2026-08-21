package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * What the map shows right now: the size of the map composable, the visible area, and conversions
 * between geographic positions and screen locations.
 *
 * Read a current instance from [CameraState.viewport]. The instance is immutable; a new one
 * replaces it when the map has adopted a new camera or a new size, so a composition that reads any
 * of its properties recomposes exactly when the answers change. All properties of one instance
 * describe the same rendered transform, so they are consistent with each other.
 */
@Immutable
public class Viewport
internal constructor(
  /** The size of the map composable this viewport was computed for. */
  public val size: DpSize,

  /**
   * The smallest bounding box that contains the currently visible area.
   *
   * Note that the bounding box is always a north-aligned rectangle. I.e. if the map is rotated or
   * tilted, the returned bounding box will always be larger than the actually visible area. See
   * [visibleRegion].
   */
  public val visibleBoundingBox: BoundingBox,

  /**
   * The currently visible area, which is a four-sided polygon spanned by the four points each at
   * one corner of the map composable. If the camera has tilt (pitch), this polygon is a trapezoid
   * instead of a rectangle.
   */
  public val visibleRegion: VisibleRegion,

  /** Meters per dp at the camera's target position. */
  public val metersPerDpAtTarget: Double,
  internal val map: MapAdapter,
) {
  /**
   * Returns an offset from the top-left corner of the map composable that corresponds to the given
   * [position]. This works for positions that are off-screen, too.
   *
   * The conversion answers for the map's current transform, which on a retained old instance may be
   * newer than the transform the properties above describe.
   */
  public fun screenLocationFromPosition(position: Position): DpOffset {
    return map.screenLocationFromPosition(position)
  }

  /**
   * Returns a position that corresponds to the given [offset] from the top-left corner of the map
   * composable.
   *
   * The conversion answers for the map's current transform, which on a retained old instance may be
   * newer than the transform the properties above describe.
   */
  public fun positionFromScreenLocation(offset: DpOffset): Position {
    return map.positionFromScreenLocation(offset)
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
  public suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> {
    val predicateOrNull =
      predicate.takeUnless { it == const(true) }?.compile(ExpressionContext.None)
    return map.queryRenderedFeatures(offset, layerIds, predicateOrNull)
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
  public suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> {
    val predicateOrNull =
      predicate.takeUnless { it == const(true) }?.compile(ExpressionContext.None)
    return map.queryRenderedFeatures(rect, layerIds, predicateOrNull)
  }

  @Deprecated(
    "The visible bounding box is a property of the viewport now",
    ReplaceWith("visibleBoundingBox"),
  )
  public fun queryVisibleBoundingBox(): BoundingBox = visibleBoundingBox

  @Deprecated(
    "The visible region is a property of the viewport now",
    ReplaceWith("visibleRegion"),
  )
  public fun queryVisibleRegion(): VisibleRegion = visibleRegion
}

@Deprecated("Renamed to Viewport", ReplaceWith("Viewport"))
public typealias CameraProjection = Viewport
