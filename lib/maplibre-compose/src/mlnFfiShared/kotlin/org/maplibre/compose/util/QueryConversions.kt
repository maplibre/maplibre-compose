package org.maplibre.compose.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.nativeffi.geo.Feature as FfiFeature
import org.maplibre.nativeffi.geo.FeatureIdentifier
import org.maplibre.nativeffi.geo.Geometry as FfiGeometry
import org.maplibre.nativeffi.json.JsonValue
import org.maplibre.nativeffi.query.QueriedFeature
import org.maplibre.nativeffi.query.RenderedFeatureQueryOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry as GeoJsonGeometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon

internal fun renderedQueryOptions(
  layerIds: Set<String>?,
  predicate: CompiledExpression<BooleanValue>?,
): RenderedFeatureQueryOptions? {
  if (layerIds == null && predicate == null) return null
  return RenderedFeatureQueryOptions().also {
    it.layerIds = layerIds?.toList()
    it.filter = predicate?.toStyleJson()?.toFfiJsonValue()
  }
}

/**
 * Converts a queried feature to the GeoJSON one the common API returns. Source id, source layer id,
 * and feature state ride along as synthetic properties, since `Feature` has nowhere else for them.
 */
internal fun QueriedFeature.toGeoJsonFeature(): Feature<GeoJsonGeometry, JsonObject?> {
  val base = feature.toGeoJsonFeature()
  val properties = buildMap {
    base.properties?.let { putAll(it) }
    sourceId?.let { putIfAbsent(SOURCE_ID_PROPERTY, JsonPrimitive(it)) }
    sourceLayerId?.let { putIfAbsent(SOURCE_LAYER_ID_PROPERTY, JsonPrimitive(it)) }
    state?.let { putIfAbsent(STATE_PROPERTY, it.toJsonElement()) }
  }
  return Feature(geometry = base.geometry, properties = JsonObject(properties), id = base.id)
}

/**
 * Converts a plain MapLibre feature to the GeoJSON one the common API returns. Unlike the
 * [QueriedFeature] overload, it adds no synthetic `${'$'}source` keys; a bare feature has no
 * source.
 */
internal fun FfiFeature.toGeoJsonFeature(): Feature<GeoJsonGeometry, JsonObject?> =
  Feature(
    geometry = geometry.toGeoJson(),
    properties = JsonObject(properties.associate { it.key to it.value.toJsonElement() }),
    id = identifier.toGeoJsonId(),
  )

/**
 * Converts a queried cluster feature back into the one `queryFeatureExtension` takes, restoring the
 * unsigned tag on `cluster_id` that kotlinx JSON cannot carry. MapLibre matches the variant
 * alternative exactly and treats any other numeric type as absent, returning an empty result with a
 * success status (see https://github.com/maplibre/maplibre-native-ffi/pull/340).
 *
 * Returns null when there is no usable cluster id.
 */
internal fun Feature<*, JsonObject?>.toFfiClusterFeature(): FfiFeature? {
  val clusterId = (properties?.get(CLUSTER_ID_PROPERTY) as? JsonPrimitive)?.toUnsignedOrNull()
  if (clusterId == null) return null

  val members =
    properties.orEmpty().mapNotNull { (key, value) ->
      when (key) {
        CLUSTER_ID_PROPERTY -> JsonValue.Member(key, JsonValue.UInt(clusterId))
        // Synthetic keys this conversion added on the way out; they are not MapLibre's.
        SOURCE_ID_PROPERTY,
        SOURCE_LAYER_ID_PROPERTY,
        STATE_PROPERTY -> null
        else -> JsonValue.Member(key, value.toFfiJsonValue())
      }
    }

  // Empty rather than the real geometry: mbgl reads only the properties here.
  return FfiFeature(
    geometry = FfiGeometry.Empty,
    properties = members,
    identifier = FeatureIdentifier.Null,
  )
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
  if (asDouble < 0.0 || asDouble != Math.floor(asDouble)) return null
  return asDouble.toLong()
}

/** Property key carrying the source a queried feature came from. */
internal const val SOURCE_ID_PROPERTY = "\$source"

/** Property key carrying the source layer a queried feature came from. */
internal const val SOURCE_LAYER_ID_PROPERTY = "\$sourceLayer"

/** Property key carrying the feature state at query time. */
internal const val STATE_PROPERTY = "\$state"

private fun FeatureIdentifier.toGeoJsonId(): JsonPrimitive? =
  when (this) {
    is FeatureIdentifier.Null -> null
    // Rendered as unsigned, because the C ABI carries uint64_t in a Long's bit pattern and a large
    // id would otherwise read back negative.
    is FeatureIdentifier.UInt -> JsonPrimitive(value.toULong())
    is FeatureIdentifier.Int -> JsonPrimitive(value)
    is FeatureIdentifier.DoubleValue -> JsonPrimitive(value)
    is FeatureIdentifier.StringValue -> JsonPrimitive(value)
    // Only when the FFI is newer than this build; an unidentified feature is still usable.
    is FeatureIdentifier.Unknown -> null
  }

private fun FfiGeometry.toGeoJson(): GeoJsonGeometry =
  when (this) {
    is FfiGeometry.Point -> Point(coordinate.toPosition())
    is FfiGeometry.LineString -> LineString(coordinates.map { it.toPosition() })
    is FfiGeometry.Polygon -> Polygon(rings.map { ring -> ring.map { it.toPosition() } })
    is FfiGeometry.MultiPoint -> MultiPoint(coordinates.map { it.toPosition() })
    is FfiGeometry.MultiLineString ->
      MultiLineString(lines.map { line -> line.map { it.toPosition() } })
    is FfiGeometry.MultiPolygon ->
      MultiPolygon(polygons.map { polygon -> polygon.map { ring -> ring.map { it.toPosition() } } })
    is FfiGeometry.Collection -> GeometryCollection(geometries.map { it.toGeoJson() })
    // Degrade to an empty collection so one unrecognized shape does not fail the whole query.
    is FfiGeometry.Unknown -> GeometryCollection(emptyList())
    is FfiGeometry.Empty -> GeometryCollection(emptyList())
  }
