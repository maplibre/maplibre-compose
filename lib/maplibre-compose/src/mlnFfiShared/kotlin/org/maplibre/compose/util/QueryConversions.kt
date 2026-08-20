package org.maplibre.compose.util

import kotlin.math.floor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.nativeffi.query.QueriedFeature
import org.maplibre.nativeffi.query.RenderedFeatureQueryOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry as GeoJsonGeometry

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

/** Decodes each hit's GeoJSON Feature. Source ids and feature state stay on [QueriedFeature]. */
internal fun List<QueriedFeature>.toGeoJsonFeatures(): List<Feature<GeoJsonGeometry, JsonObject?>> =
  mapNotNull {
    it.toGeoJsonFeature()
  }

internal fun QueriedFeature.toGeoJsonFeature(): Feature<GeoJsonGeometry, JsonObject?>? =
  Feature.fromJsonOrNull<GeoJsonGeometry, JsonObject?>(feature.decodeToString())

/**
 * Converts a queried cluster feature back into the GeoJSON `queryFeatureExtension` takes, keeping
 * `cluster_id` an unsigned integer literal: MapLibre resolves the cluster by the value's exact
 * numeric type and treats any other as absent, returning an empty result with a success status (see
 * https://github.com/maplibre/maplibre-native-ffi/pull/340).
 *
 * Returns null when there is no usable cluster id.
 */
internal fun Feature<*, JsonObject?>.toFfiClusterFeature(): ByteArray? {
  val clusterId = (properties?.get(CLUSTER_ID_PROPERTY) as? JsonPrimitive)?.toUnsignedOrNull()
  if (clusterId == null) return null

  val feature = buildJsonObject {
    put("type", "Feature")
    // Null rather than the real geometry: mbgl reads only the properties here.
    put("geometry", JsonNull)
    putJsonObject("properties") {
      properties.orEmpty().forEach { (key, value) ->
        when (key) {
          // uint64_t crosses JSON as an integer literal; a Long's bit pattern reads back unsigned.
          CLUSTER_ID_PROPERTY -> put(key, JsonPrimitive(clusterId.toULong()))
          else -> put(key, value)
        }
      }
    }
  }
  return feature.toJsonBytes()
}

/** The property MapLibre puts a cluster's id in, and the only one a cluster query reads. */
internal const val CLUSTER_ID_PROPERTY = "cluster_id"

/**
 * Reads a non-negative integer, whatever shape it arrived in: a caller-built feature may carry the
 * id quoted or as a double.
 */
private fun JsonPrimitive.toUnsignedOrNull(): Long? {
  content.toULongOrNull()?.let {
    return it.toLong()
  }
  val asDouble = content.toDoubleOrNull() ?: return null
  if (asDouble < 0.0 || asDouble != floor(asDouble)) return null
  return asDouble.toLong()
}
