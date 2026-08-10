package org.maplibre.compose.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.toJson

internal fun JsonObjectBuilder.putTileSetOptions(options: TileSetOptions) {
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

internal fun GeoJsonData.toDataJson(): JsonElement =
  when (this) {
    is GeoJsonData.Uri -> JsonPrimitive(uri)
    is GeoJsonData.JsonString -> Json.parseToJsonElement(json)
    is GeoJsonData.Features -> Json.parseToJsonElement(geoJson.toJson())
  }

/**
 * `minzoom` and `synchronousUpdate` are deliberately absent: the spec has no place for them here
 * and GL JS rejects the whole source over an unknown key, so each backend that honours one writes
 * it itself.
 */
internal fun JsonObjectBuilder.putGeoJsonOptions(options: GeoJsonOptions) {
  put("maxzoom", options.maxZoom)
  put("buffer", options.buffer)
  put("tolerance", options.tolerance)
  put("lineMetrics", options.lineMetrics)
  put("cluster", options.cluster)
  put("clusterRadius", options.clusterRadius)
  put("clusterMaxZoom", options.clusterMaxZoom)
  put("clusterMinPoints", options.clusterMinPoints)
  if (options.clusterProperties.isEmpty()) return
  putJsonObject("clusterProperties") {
    options.clusterProperties.forEach { (name, aggregator) ->
      // Reducer first, then mapper: the style spec's pair is [operator, map expression].
      putJsonArray(name) {
        add(aggregator.reducer.compile(ExpressionContext.None).toStyleJson())
        add(aggregator.mapper.compile(ExpressionContext.None).toStyleJson())
      }
    }
  }
}
