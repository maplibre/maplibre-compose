package org.maplibre.compose.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.nativeffi.geo.FeatureIdentifier
import org.maplibre.nativeffi.geo.Geometry as FfiGeometry
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
  val properties = buildMap {
    feature.properties.forEach { put(it.key, it.value.toJsonElement()) }
    sourceId?.let { put(SOURCE_ID_PROPERTY, JsonPrimitive(it)) }
    sourceLayerId?.let { put(SOURCE_LAYER_ID_PROPERTY, JsonPrimitive(it)) }
    state?.let { put(STATE_PROPERTY, it.toJsonElement()) }
  }

  return Feature(
    geometry = feature.geometry.toGeoJson(),
    properties = JsonObject(properties),
    id = feature.identifier.toGeoJsonId(),
  )
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
