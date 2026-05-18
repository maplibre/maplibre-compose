package org.maplibre.compose.sources

import org.maplibre.kmp.native.style.sources.GeoJsonSource as MLNGeoJsonSource
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection

public actual class ComputedSource : Source {
  override val impl: MLNGeoJsonSource

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>,
  ) : super() {
    impl = MLNGeoJsonSource(id)
  }

  public actual fun invalidateBounds(bounds: BoundingBox) {}

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {}

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection<*, *>) {}
}
