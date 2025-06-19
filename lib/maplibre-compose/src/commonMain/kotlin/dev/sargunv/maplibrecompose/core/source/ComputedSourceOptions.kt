package dev.sargunv.maplibrecompose.core.source

import androidx.compose.runtime.Immutable

/**
 * @param minZoom Minimum zoom level at which to create vector tiles (lower means more field of view
 *   detail at low zoom levels).
 * @param maxZoom Maximum zoom level at which to create vector tiles (higher means greater detail at
 *   high zoom levels).
 * @param buffer Size of the tile buffer on each side. A value of 0 produces no buffer. A value of
 *   512 produces a buffer as wide as the tile itself. Larger values produce fewer rendering
 *   artifacts near tile edges at the cost of slower performance.
 * @param tolerance Douglas-Peucker simplification tolerance (higher means simpler geometries and
 *   faster performance).
 * @param clip If the data includes geometry outside the tile boundaries, setting this to true clips
 *   the geometry to the tile boundaries.
 * @param wrap If the data includes wrapped coordinates, setting this to true unwraps the
 *   coordinates.
 */
@Immutable
public data class ComputedSourceOptions(
  val minZoom: Int = Defaults.MIN_ZOOM,
  val maxZoom: Int = Defaults.MAX_ZOOM,
  val buffer: Int = 128,
  val tolerance: Float = 0.375f,
  val clip: Boolean = false,
  val wrap: Boolean = false,
)
