package dev.sargunv.maplibrecompose.core.source

import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.FeatureCollection

public actual class ComputedSource : Source {
  override val impl: Nothing = TODO("Not yet implemented")

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (BoundingBox, Int) -> FeatureCollection,
  )

  public actual fun invalidateBounds(bounds: BoundingBox) {}

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {}

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection) {}
}
