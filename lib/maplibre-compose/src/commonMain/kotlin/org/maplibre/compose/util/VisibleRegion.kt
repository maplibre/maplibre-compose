package org.maplibre.compose.util

import org.maplibre.spatialk.geojson.Position

/**
 * Four-sided polygon representing the visible area of the map composable. If the camera has tilt
 * (pitch), this polygon is a trapezoid instead of a rectangle.
 */
public data class VisibleRegion(
  /** Position corresponding to the top-left corner of the map composable */
  val farLeft: Position,
  /** Position corresponding to the top-right corner of the map composable */
  val farRight: Position,
  /** Position corresponding to the bottom-left corner of the map composable */
  val nearLeft: Position,
  /** Position corresponding to the bottom-right corner of the map composable */
  val nearRight: Position,
)
