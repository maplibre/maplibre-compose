package dev.sargunv.maplibrecompose.core.source

import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.FeatureCollection

public actual class ComputedSource : Source {

  @Suppress("UNREACHABLE_CODE") override val impl: Nothing = TODO()

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (BoundingBox, Int) -> FeatureCollection,
  )
}
