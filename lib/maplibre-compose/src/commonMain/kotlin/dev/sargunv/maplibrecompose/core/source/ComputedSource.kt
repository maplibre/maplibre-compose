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
}
