package org.maplibre.compose.util

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.gljs.GeoJsonFeature
import org.maplibre.compose.gljs.isJsNullish
import org.maplibre.compose.gljs.jsUnsafeCast
import org.maplibre.compose.gljs.parseJson
import org.maplibre.compose.gljs.stringifyJson
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** [T] is unchecked; nothing verifies that the JSON matches it. */
internal fun <T : JsAny?> JsonElement.toJsValue(): T = jsUnsafeCast(parseJson(toString()))

internal fun JsAny?.toJsonElement(): JsonElement {
  if (isJsNullish(this)) return JsonNull
  val text = stringifyJson(this) ?: return JsonNull
  return Json.parseToJsonElement(text)
}

internal fun GeoJsonFeature.toGeoJsonFeature(): Feature<Geometry, JsonObject?> =
  Feature.fromJson(stringifyJson(this) ?: "null")

internal fun JsArray<out GeoJsonFeature>.toFeatureCollection():
  FeatureCollection<Geometry, JsonObject?> =
  FeatureCollection(toList().map { it.toGeoJsonFeature() })
