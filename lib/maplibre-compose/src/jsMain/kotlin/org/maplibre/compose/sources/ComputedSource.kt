package org.maplibre.compose.sources

import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.FeatureCollection

public actual class ComputedSource : Source {

  @Suppress("UNREACHABLE_CODE") override val impl: Nothing = TODO()

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection,
  )

  public actual fun invalidateBounds(bounds: BoundingBox) {}

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {}

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection) {}
}
