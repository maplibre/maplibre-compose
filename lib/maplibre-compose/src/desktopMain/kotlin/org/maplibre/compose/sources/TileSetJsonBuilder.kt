package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds a TileJSON config string for tile-based sources (vector, raster, raster-dem). The
 * resulting JSON object can be parsed by the C++ side into a Tileset struct.
 */
internal fun buildTileSetJson(
  tiles: List<String>,
  options: TileSetOptions,
  tileSize: Int? = null,
  demEncoding: String? = null,
): String =
  buildJsonObject {
      put("tiles", JsonArray(tiles.map { JsonPrimitive(it) }))
      put("minzoom", options.minZoom)
      put("maxzoom", options.maxZoom)
      put("scheme", options.tileCoordinateSystem.name.lowercase())
      options.attributionHtml?.let { put("attribution", it) }
      options.boundingBox?.let { bbox ->
        put(
          "bounds",
          JsonArray(
            listOf(
              JsonPrimitive(bbox.west),
              JsonPrimitive(bbox.south),
              JsonPrimitive(bbox.east),
              JsonPrimitive(bbox.north),
            )
          ),
        )
      }
      tileSize?.let { put("tileSize", it) }
      demEncoding?.let { put("encoding", it) }
    }
    .toString()
