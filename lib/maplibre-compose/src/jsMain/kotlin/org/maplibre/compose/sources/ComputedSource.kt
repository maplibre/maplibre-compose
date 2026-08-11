package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection

// TODO(#869): implement this over addProtocol, once something encodes MVT in Kotlin.

/** Construction always throws on Kotlin/JS. */
public actual class ComputedSource
public actual constructor(
  id: String,
  options: ComputedSourceOptions,
  getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>,
) : Source(id) {

  init {
    throw UnsupportedOperationException(
      "ComputedSource is not available in the browser: MapLibre GL JS has no source type that " +
        "computes its features from a callback. Use a GeoJsonSource and set its data instead."
    )
  }

  override fun toJson(): JsonObject = buildJsonObject {}

  public actual fun invalidateBounds(bounds: BoundingBox): Unit = Unit

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int): Unit = Unit

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection<*, *>): Unit =
    Unit
}
