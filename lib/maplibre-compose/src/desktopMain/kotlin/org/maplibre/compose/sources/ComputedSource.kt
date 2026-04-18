package org.maplibre.compose.sources

import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection

/** ComputedSource (custom geometry source) is not yet supported on desktop. */
public actual class ComputedSource : Source {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val _sourceId: String

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>,
  ) {
    _sourceId = id
  }

  override fun toJson(): String = """{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}"""

  public actual fun invalidateBounds(bounds: BoundingBox) {}

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {}

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection<*, *>) {}
}
