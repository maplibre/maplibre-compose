@file:JvmName("DesktopRasterDemSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

public actual class RasterDemSource : Source {

  // A tiled source has no mutable properties in the common API, so its definition is fixed here.
  private val json: JsonObject

  public actual constructor(id: String, uri: String, tileSize: Int) : super(id) {
    json = buildJsonObject {
      put("type", "raster-dem")
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
    demEncoding: RasterDemEncoding,
  ) : super(id) {
    json = buildJsonObject {
      put("type", "raster-dem")
      putJsonArray("tiles") { tiles.forEach { add(it) } }
      put("tileSize", tileSize)
      put(
        "encoding",
        // MapLibre Native rejects the source outright on anything but mapbox or terrarium, so a
        // custom encoding decodes as Mapbox rather than not loading.
        // https://github.com/maplibre/maplibre-native/issues/2783
        when (demEncoding) {
          is RasterDemEncoding.Custom -> RasterDemEncoding.Mapbox.value
          else -> demEncoding.value
        },
      )
      putTileSetOptions(options)
    }
  }

  override fun toJson(): JsonObject = json
}

/** Writes the TileJSON fields that the style spec shares across all tiled sources. */
private fun JsonObjectBuilder.putTileSetOptions(options: TileSetOptions) {
  put("minzoom", options.minZoom)
  put("maxzoom", options.maxZoom)
  put(
    "scheme",
    when (options.tileCoordinateSystem) {
      TileCoordinateSystem.XYZ -> "xyz"
      TileCoordinateSystem.TMS -> "tms"
    },
  )
  options.boundingBox?.let { box ->
    putJsonArray("bounds") {
      add(box.west)
      add(box.south)
      add(box.east)
      add(box.north)
    }
  }
  options.attributionHtml?.let { put("attribution", it) }
}
