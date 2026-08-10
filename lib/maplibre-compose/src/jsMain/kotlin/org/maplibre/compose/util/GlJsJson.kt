package org.maplibre.compose.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.gljs.GeoJsonFeature
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * The bridge between kotlinx JSON and the plain JavaScript values MapLibre GL JS speaks, through
 * the serialized form: both sides already agree on JSON exactly. [T] is whichever of MapLibre's
 * declared shapes the caller is building; nothing checks that the JSON matches it.
 */
internal fun <T> JsonElement.toJsValue(): T = JSON.parse(toString())

/** The kotlinx form of a value MapLibre handed back, or [kotlinx.serialization.json.JsonNull]. */
internal fun Any?.toJsonElement(): JsonElement {
  // Kotlin compiles this to a loose comparison, so it catches `undefined` too.
  if (this == null) return JsonNull
  // `undefined` anywhere in the value serializes to nothing at all, which the declared return type
  // of JSON.stringify does not admit.
  val text = JSON.stringify(this).unsafeCast<String?>() ?: return JsonNull
  return Json.parseToJsonElement(text)
}

/** A rendered or queried feature, in the common API's GeoJSON types. */
internal fun GeoJsonFeature.toGeoJsonFeature(): Feature<Geometry, JsonObject?> =
  Feature.fromJson(JSON.stringify(this))

/** Cluster children and leaves, which MapLibre answers as a bare array of GeoJSON features. */
internal fun Array<GeoJsonFeature>.toFeatureCollection(): FeatureCollection<Geometry, JsonObject?> =
  FeatureCollection(map { it.toGeoJsonFeature() })
