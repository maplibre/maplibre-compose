package org.maplibre.compose.camera

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.util.VisibleRegion

/**
 * Provides an imperative API to interact with the projection of the map, such as converting
 * coordinates or querying what's visible.
 */
public class CameraProjection internal constructor(internal val map: MapAdapter) {
  /**
   * Returns an offset from the top-left corner of the map composable that corresponds to the given
   * [position]. This works for positions that are off-screen, too.
   */
  public fun screenLocationFromPosition(position: Position): DpOffset {
    return map.screenLocationFromPosition(position)
  }

  /**
   * Returns a position that corresponds to the given [offset] from the top-left corner of the map
   * composable.
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
  public fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature> {
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
  public fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature> {
    val predicateOrNull =
      predicate.takeUnless { it == const(true) }?.compile(ExpressionContext.None)
    return map.queryRenderedFeatures(rect, layerIds, predicateOrNull)
  }

  /**
   * Returns the smallest bounding box that contains the currently visible area.
   *
   * Note that the bounding box is always a north-aligned rectangle. I.e. if the map is rotated or
   * tilted, the returned bounding box will always be larger than the actually visible area. See
   * [queryVisibleRegion].
   */
  public fun queryVisibleBoundingBox(): BoundingBox {
    // TODO at some point, this should be refactored to State, just like the camera position
    return map.getVisibleBoundingBox()
  }

  /**
   * Returns the currently visible area, which is a four-sided polygon spanned by the four points
   * each at one corner of the map composable. If the camera has tilt (pitch), this polygon is a
   * trapezoid instead of a rectangle.
   */
  public fun queryVisibleRegion(): VisibleRegion {
    // TODO at some point, this should be refactored to State, just like the camera position
    return map.getVisibleRegion()
  }
}
