@file:JvmName("MlnFfiRasterSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

public actual class RasterSource : Source {

  private val json: JsonObject

  public actual constructor(id: String, uri: String, tileSize: Int) : super(id) {
    json = buildJsonObject {
      put("type", "raster")
      put("url", uri)
      // "tileSize" is one of the few camelCase names in the style spec; "tilesize" is ignored.
      put("tileSize", tileSize)
    }
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
  ) : super(id) {
    json = buildJsonObject {
      put("type", "raster")
      putJsonArray("tiles") { tiles.forEach { add(it) } }
      put("tileSize", tileSize)
      putTileSetOptions(options)
    }
  }

  override fun toJson(): JsonObject = json
}
