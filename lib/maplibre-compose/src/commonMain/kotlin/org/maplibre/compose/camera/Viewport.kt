package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.DpSize
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * The map composable's size and visible area.
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
   * This north-aligned rectangle can include areas outside [visibleRegion] when the map is rotated
   * or tilted.
   */
  public val visibleBoundingBox: BoundingBox,

  /**
   * The polygon formed by the map composable's four corners. Camera tilt makes it a trapezoid
   * instead of a rectangle.
   */
  public val visibleRegion: VisibleRegion,

  /** Meters per dp at the camera's target position. */
  public val metersPerDpAtTarget: Double,
)
