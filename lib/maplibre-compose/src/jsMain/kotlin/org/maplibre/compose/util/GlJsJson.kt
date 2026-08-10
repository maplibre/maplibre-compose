package org.maplibre.compose.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.gljs.GeoJsonFeature
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** [T] is unchecked; nothing verifies that the JSON matches it. */
internal fun <T> JsonElement.toJsValue(): T = JSON.parse(toString())

internal fun Any?.toJsonElement(): JsonElement {
  // Kotlin compiles this to a loose comparison, so it catches `undefined` too.
  if (this == null) return JsonNull
  // JSON.stringify returns `undefined` for values that serialize to nothing, which its declared
  // return type does not admit.
  val text = JSON.stringify(this).unsafeCast<String?>() ?: return JsonNull
  return Json.parseToJsonElement(text)
}

internal fun GeoJsonFeature.toGeoJsonFeature(): Feature<Geometry, JsonObject?> =
  Feature.fromJson(JSON.stringify(this))

internal fun Array<GeoJsonFeature>.toFeatureCollection(): FeatureCollection<Geometry, JsonObject?> =
  FeatureCollection(map { it.toGeoJsonFeature() })
