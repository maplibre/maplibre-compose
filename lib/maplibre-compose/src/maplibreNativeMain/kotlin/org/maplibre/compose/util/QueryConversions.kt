package org.maplibre.compose.util

import kotlin.math.floor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.sources.ClusterIdProperty
import org.maplibre.nativeffi.RenderedFeature
import org.maplibre.nativeffi.RenderedFeatureQueryOptions
import org.maplibre.nativeffi.SourceFeature
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry as GeoJsonGeometry

internal fun renderedQueryOptions(
  layerIds: Set<String>?,
  predicate: CompiledExpression<BooleanValue>?,
): RenderedFeatureQueryOptions? {
  if (layerIds == null && predicate == null) return null
  return RenderedFeatureQueryOptions(
    layerIds = layerIds?.toList(),
    filter = predicate?.toStyleJson()?.toString().orEmpty(),
  )
}

/** Decodes each hit's GeoJSON Feature. Source ids and feature state stay on [RenderedFeature]. */
internal suspend fun List<RenderedFeature>.toGeoJsonFeatures():
  List<Feature<GeoJsonGeometry, JsonObject?>> {
  val context = currentCoroutineContext()
  return mapNotNull {
    context.ensureActive()
    it.toGeoJsonFeature()
  }
}

internal fun RenderedFeature.toGeoJsonFeature(): Feature<GeoJsonGeometry, JsonObject?>? =
  Feature.fromJsonOrNull<GeoJsonGeometry, JsonObject?>(feature)

/** Decodes source-query hits independently of rendered-layer metadata. */
@kotlin.jvm.JvmName("sourceFeaturesToGeoJson")
internal suspend fun List<SourceFeature>.toGeoJsonFeatures():
  List<Feature<GeoJsonGeometry, JsonObject?>> {
  val context = currentCoroutineContext()
  return mapNotNull {
    context.ensureActive()
    Feature.fromJsonOrNull<GeoJsonGeometry, JsonObject?>(it.feature)
  }
}

/** The feature's `cluster_id`, or null when it has none that a cluster query can use. */
internal fun Feature<*, JsonObject?>.clusterIdOrNull(): UInt? =
  (properties?.get(ClusterIdProperty) as? JsonPrimitive)?.toUIntOrNull()

/**
 * Reads a non-negative integer, whatever shape it arrived in: a caller-built feature may carry the
 * id quoted or as a double.
 */
private fun JsonPrimitive.toUIntOrNull(): UInt? {
  content.toUIntOrNull()?.let {
    return it
  }
  val asDouble = content.toDoubleOrNull() ?: return null
  if (asDouble < 0.0 || asDouble > UInt.MAX_VALUE.toDouble() || asDouble != floor(asDouble))
    return null
  return asDouble.toUInt()
}
