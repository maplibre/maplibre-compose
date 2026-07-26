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

/** Builds the FFI query options from the layer and predicate a caller supplied. */
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
 * Converts a queried feature to the GeoJSON one the common API returns.
 *
 * Source id, source layer id, and feature state are preserved as properties rather than dropped:
 * the common `Feature` has nowhere else to carry them, and a caller distinguishing hits across
 * layers needs at least the source.
 */
internal fun QueriedFeature.toGeoJsonFeature(): Feature<GeoJsonGeometry, JsonObject?> {
  val base = feature.toGeoJsonFeature()
  val properties = buildMap {
    base.properties?.let { putAll(it) }
    sourceId?.let { put(SOURCE_ID_PROPERTY, JsonPrimitive(it)) }
    sourceLayerId?.let { put(SOURCE_LAYER_ID_PROPERTY, JsonPrimitive(it)) }
    state?.let { put(STATE_PROPERTY, it.toJsonElement()) }
  }
  return Feature(geometry = base.geometry, properties = JsonObject(properties), id = base.id)
}

/**
 * Converts a plain MapLibre feature to the GeoJSON one the common API returns.
 *
 * Kept separate from the [QueriedFeature] overload because only a queried feature has a source to
 * record: cluster children and leaves come back as bare features, and giving them the synthetic
 * `${'$'}source` keys would be inventing information.
 */
internal fun FfiFeature.toGeoJsonFeature(): Feature<GeoJsonGeometry, JsonObject?> =
  Feature(
    geometry = geometry.toGeoJson(),
    properties = JsonObject(properties.associate { it.key to it.value.toJsonElement() }),
    id = identifier.toGeoJsonId(),
  )

/**
 * Converts a queried cluster feature back into the one `queryFeatureExtension` takes.
 *
 * Only `cluster_id` actually matters — MapLibre reads it from the properties and ignores the
 * geometry and the identifier entirely — but it has to arrive as an *unsigned* integer. MapLibre
 * looks it up with an exact check against the stored variant alternative, and the mismatch does not
 * fail: the lookup simply misses and the query returns an empty result with a success status.
 *
 * The FFI is not where that is lost. Its `JsonValue` is a tagged union that keeps `UInt` and `Int`
 * distinct in both directions, so a queried feature reaches Kotlin with the tag intact. It is lost
 * *here*, because the public API hands callers a `Feature<Geometry, JsonObject?>` and kotlinx JSON
 * has no unsigned integer to hold it in. So this is not a workaround for anything upstream, and no
 * FFI change removes it: as long as a cluster feature round-trips through GeoJSON, the tag has to
 * be restored on the way back.
 *
 * The contract is spelled out upstream by https://github.com/maplibre/maplibre-native-ffi/pull/340,
 * which documents that any other numeric type is treated as absent rather than rejected. That PR
 * does not change the behavior, so this conversion is not a workaround waiting on it.
 *
 * Returns null when there is no usable cluster id, so a caller can skip the query rather than run
 * one that cannot match.
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

  // Empty rather than the real geometry: mbgl reads only the properties here, and converting a
  // geometry that will be discarded is work with a chance of being wrong.
  return FfiFeature(
    geometry = FfiGeometry.Empty,
    properties = members,
    identifier = FeatureIdentifier.Null,
  )
}

/** The property MapLibre puts a cluster's id in, and the only one a cluster query reads. */
internal const val CLUSTER_ID_PROPERTY = "cluster_id"

/**
 * Reads a non-negative integer, whatever shape it arrived in.
 *
 * The round trip through a rendered query normally yields an unquoted unsigned literal, but a
 * caller may hand back a feature they built themselves, where the id could be quoted or have gone
 * through a double.
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
    is FeatureIdentifier.UInt -> JsonPrimitive(value.toULong().toString())
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
    // Only reachable when the FFI is newer than this build. An empty collection keeps the rest of
    // the query result usable rather than failing the whole call for one unrecognized shape.
    is FfiGeometry.Unknown -> GeometryCollection(emptyList())
    // Same reasoning as Unknown: keep the rest of the result usable.
    is FfiGeometry.Empty -> GeometryCollection(emptyList())
  }
