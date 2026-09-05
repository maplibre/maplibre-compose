package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.DpSize
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * What the map shows right now: the size of the map composable and the visible area.
 *
 * Read a current instance from [org.maplibre.compose.map.MapState.viewport]. A new immutable
 * instance replaces it when the map has adopted a new camera or size. A composition that reads any
 * property recomposes exactly when the value changes. All properties of one instance describe the
 * same rendered transform and are consistent with each other.
 */
@Immutable
public data class Viewport
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
)
