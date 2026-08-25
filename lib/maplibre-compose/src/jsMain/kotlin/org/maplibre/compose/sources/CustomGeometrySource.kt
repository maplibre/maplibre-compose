package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.spatialk.geojson.BoundingBox

/** Construction always throws on Kotlin/JS. */
public actual class CustomGeometrySource
public actual constructor(
  id: String,
  options: CustomGeometrySourceOptions,
  provider: GeometryTileProvider,
) : Source(id) {

  init {
    throw UnsupportedOperationException(
      "CustomGeometrySource is not available in the browser. Use CustomVectorSource when the " +
        "provider can return MVT data, or use GeoJsonSource for geographic features."
    )
  }

  override fun toJson(): JsonObject = buildJsonObject {}

  public actual fun invalidateBounds(bounds: BoundingBox): Unit = Unit

  public actual fun invalidateTile(tile: TileCoordinate): Unit = Unit
}
