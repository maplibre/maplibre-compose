@file:JvmName("DesktopVectorSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

public actual class VectorSource : Source {

  // A tiled source has no mutable properties in the common API, so its definition is fixed at
  // construction and the descriptor is just this object.
  private val json: JsonObject

  public actual constructor(id: String, uri: String) : super(id) {
    json = buildJsonObject {
      put("type", "vector")
      put("url", uri)
    }
  }

  public actual constructor(id: String, tiles: List<String>, options: TileSetOptions) : super(id) {
    json = buildJsonObject {
      put("type", "vector")
      putJsonArray("tiles") { tiles.forEach { add(it) } }
      putTileSetOptions(options)
    }
  }

  override fun toJson(): JsonObject = json

  public actual fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue>,
  ): List<Feature<Geometry, JsonObject?>> {
    // TODO(maplibre-native-ffi): source feature queries only exist on RenderSessionHandle, which a
    // source cannot reach through its style binding. MapHandle needs a querySourceFeatures taking a
    // source id, source-layer ids, and a filter expression, returning the matching features.
    return emptyList()
  }
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
