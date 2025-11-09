package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection

/** A map data source of tiled vector data generated with some custom logic */
public expect class ComputedSource : Source {

  /**
   * @param id Unique identifier for this source
   * @param getFeatures A function that retrieves a `FeatureCollection` for the given `BoundingBox`
   *   and zoom level.
   * @param options see [ComputedSourceOptions]
   */
  public constructor(
    id: String,
    options: ComputedSourceOptions = ComputedSourceOptions(),
    getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>,
  )

  /**
   * Invalidate previously provided features within a given bounds at all zoom levels. Invoking this
   * method will result in new requests to `getFeatures` for regions that contain, include, or
   * intersect with the provided bounds.
   */
  public fun invalidateBounds(bounds: BoundingBox)

  /**
   * Invalidate the geometry contents of a specific tile. Invoking this method will result in new
   * requests to `getFeatures` for visible tiles.
   */
  public fun invalidateTile(zoomLevel: Int, x: Int, y: Int)

  /**
   * Set or update geometry contents of a specific tile. Use this method to update tiles for which
   * `getFeatures` was previously invoked.
   */
  public fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection<*, *>)
}

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
  val minZoom: Int = SourceDefaults.MIN_ZOOM,
  val maxZoom: Int = SourceDefaults.MAX_ZOOM,
  val buffer: Int = 128,
  val tolerance: Float = 0.375f,
  val clip: Boolean = false,
  val wrap: Boolean = false,
)

/**
 * Remember a new [ComputedSource] with the given [options] from the given [getFeatures] function.
 */
@Composable
public fun rememberComputedSource(
  options: ComputedSourceOptions = ComputedSourceOptions(),
  getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>,
): ComputedSource =
  key(options, getFeatures) {
    rememberUserSource(
      factory = { ComputedSource(id = it, options = options, getFeatures = getFeatures) },
      update = {},
    )
  }
