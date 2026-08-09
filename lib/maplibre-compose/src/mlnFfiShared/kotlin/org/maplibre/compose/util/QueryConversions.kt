package org.maplibre.compose.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.nativeffi.query.RenderedFeatureQueryOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

internal fun renderedQueryOptions(
  layerIds: Set<String>?,
  predicate: CompiledExpression<BooleanValue>?,
): RenderedFeatureQueryOptions? {
  if (layerIds == null && predicate == null) return null
  return RenderedFeatureQueryOptions().also {
    it.layerIds = layerIds?.toList()
    it.filter = predicate?.toStyleJson()?.toJsonBytes()
  }
}

/** Decodes the query-result envelope without adding metadata to GeoJSON properties. */
internal fun ByteArray.toGeoJsonFeatures(): List<Feature<Geometry, JsonObject?>> {
  val results = Json.parseToJsonElement(decodeToString()) as? JsonArray ?: return emptyList()
  return results.map { result ->
    val feature = result.jsonObject["feature"] ?: error("Query result has no feature")
    Feature.fromJson(feature.toString())
  }
}

/**
 * Encodes a queried cluster as GeoJSON and restores the unsigned `cluster_id` representation that
 * MapLibre requires.
 *
 * Returns null when the feature contains no usable cluster id.
 */
internal fun Feature<*, JsonObject?>.toFfiClusterFeature(): ByteArray? {
  val clusterId =
    (properties?.get(CLUSTER_ID_PROPERTY) as? JsonPrimitive)?.toUnsignedOrNull() ?: return null
  return buildJsonObject {
    put("type", "Feature")
    put("geometry", JsonNull)
    putJsonObject("properties") {
      properties.orEmpty().forEach { (key, value) -> put(key, value) }
      put(CLUSTER_ID_PROPERTY, JsonPrimitive(clusterId))
    }
  }
    .toJsonBytes()
}

/** The property that MapLibre stores a cluster's identifier in. */
internal const val CLUSTER_ID_PROPERTY = "cluster_id"

/** Reads a non-negative integer from an integral number or numeric string. */
private fun JsonPrimitive.toUnsignedOrNull(): ULong? {
  content.toULongOrNull()?.let {
    return it
  }
  val asDouble = content.toDoubleOrNull() ?: return null
  if (asDouble < 0.0 || asDouble != kotlin.math.floor(asDouble)) return null
  return asDouble.toULong()
}
