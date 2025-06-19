package dev.sargunv.maplibrecompose.core.source

import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.FeatureCollection

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
    getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection,
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
  public fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection)
}
