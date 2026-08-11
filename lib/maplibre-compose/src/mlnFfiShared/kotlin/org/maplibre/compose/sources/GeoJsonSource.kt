@file:JvmName("MlnFfiGeoJsonSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.util.CLUSTER_ID_PROPERTY
import org.maplibre.compose.util.toFfiClusterFeature
import org.maplibre.compose.util.toFfiJsonValue
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.nativeffi.geo.Feature as FfiFeature
import org.maplibre.nativeffi.geo.FeatureIdentifier
import org.maplibre.nativeffi.geo.GeoJson as FfiGeoJson
import org.maplibre.nativeffi.geo.Geometry as FfiGeometry
import org.maplibre.nativeffi.geo.LatLng
import org.maplibre.nativeffi.json.JsonValue
import org.maplibre.nativeffi.query.FeatureExtensionResult
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.toJson

public actual class GeoJsonSource : Source {

  private val options: GeoJsonOptions

  // Held parsed because toJson runs again on every re-add after a style change.
  private var data: JsonElement

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : super(id) {
    this.options = options
    this.data = data.toDataJson()
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", data)
    putGeoJsonOptions(options)
    // Neither is in the style spec's GeoJSON source, but MapLibre Native reads both straight off
    // the source JSON.
    put("minzoom", options.minZoom)
    put("synchronousUpdate", options.synchronousUpdate)
  }

  public actual fun setData(data: GeoJsonData) {
    this.data = data.toDataJson()
    if (data is GeoJsonData.Uri) {
      mutate { map -> map.setGeoJsonSourceUrl(id, data.uri) }
    } else {
      // Converted outside the lambda so the map's owner thread does not walk the data.
      val geoJson = this.data.toFfiGeoJson()
      mutate { map -> map.setGeoJsonSourceData(id, geoJson) }
    }
  }

  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean {
    return CLUSTER_ID_PROPERTY in feature.properties.orEmpty()
  }

  public actual suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    val result = queryClusterExtension(feature, EXPANSION_ZOOM_FIELD)
    val value = (result as? FeatureExtensionResult.Value)?.value
    return when (value) {
      // MapLibre computes the zoom as a uint64_t.
      is JsonValue.UInt -> value.value.toULong().toDouble()
      is JsonValue.Int -> value.value.toDouble()
      is JsonValue.DoubleValue -> value.value
      else -> {
        reportMiss(EXPANSION_ZOOM_FIELD, result)
        NO_EXPANSION_ZOOM
      }
    }
  }

  public actual suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> = queryClusterFeatures(feature, CHILDREN_FIELD, null)

  public actual suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> =
    queryClusterFeatures(
      feature,
      LEAVES_FIELD,
      // Both must be unsigned: MapLibre type-checks them exactly and silently falls back to its own
      // default of ten otherwise, and it ignores offset unless limit is present.
      // https://github.com/maplibre/maplibre-native-ffi/pull/340
      JsonValue.ObjectValue(
        listOf(
          JsonValue.Member("limit", JsonValue.UInt(limit.coerceAtLeast(0))),
          JsonValue.Member("offset", JsonValue.UInt(offset.coerceAtLeast(0))),
        )
      ),
    )

  /**
   * Runs one supercluster query against the render session. Returns null when the feature carries
   * no cluster id, when no render session is attached yet, or when the query failed.
   */
  private fun queryClusterExtension(
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: JsonValue? = null,
  ): FeatureExtensionResult? {
    val ffiFeature = feature.toFfiClusterFeature() ?: return null
    return binding.withRenderSession { session ->
      session.queryFeatureExtension(id, ffiFeature, SUPERCLUSTER_EXTENSION, field, arguments)
    }
  }

  private fun queryClusterFeatures(
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: JsonValue?,
  ): FeatureCollection<*, JsonObject?> {
    val result = queryClusterExtension(feature, field, arguments)
    val features =
      when (result) {
        is FeatureExtensionResult.FeatureCollection -> result.features.map { it.toGeoJsonFeature() }
        else -> {
          reportMiss(field, result)
          emptyList()
        }
      }
    return FeatureCollection(features)
  }

  /**
   * Reports a lookup that found no cluster. MapLibre answers a successful query with a feature
   * collection, even an empty one, and a failed one with a null value.
   */
  private fun reportMiss(field: String, result: FeatureExtensionResult?) {
    if (result == null) return
    binding.logger?.w {
      "Cluster '$field' query matched no cluster in source '$id'; the feature's cluster_id is " +
        "probably stale. MapLibre answered with $result."
    }
  }

  private companion object {
    /** The only extension MapLibre answers for a GeoJSON source; anything else returns nothing. */
    const val SUPERCLUSTER_EXTENSION = "supercluster"

    const val EXPANSION_ZOOM_FIELD = "expansion-zoom"
    const val CHILDREN_FIELD = "children"
    const val LEAVES_FIELD = "leaves"

    /** Reported when the cluster has no expansion zoom to give; matches Android. */
    const val NO_EXPANSION_ZOOM = 0.0
  }
}

/** Converts caller-supplied features into the FFI's geometry tree. */
internal fun FeatureCollection<*, *>.toFfiGeoJson(): FfiGeoJson =
  Json.parseToJsonElement(toJson()).toFfiGeoJson()

/** Converts parsed GeoJSON into the FFI's geometry tree. */
private fun JsonElement.toFfiGeoJson(): FfiGeoJson {
  val obj =
    this as? JsonObject ?: throw IllegalArgumentException("GeoJSON data must be a JSON object")
  return when (obj.typeName()) {
    "FeatureCollection" ->
      FfiGeoJson.FeatureCollection(obj["features"].asArray().map { it.asObject().toFfiFeature() })

    "Feature" -> FfiGeoJson.FeatureValue(obj.toFfiFeature())
    else -> FfiGeoJson.GeometryValue(obj.toFfiGeometry())
  }
}

private fun JsonObject.toFfiFeature(): FfiFeature =
  FfiFeature(
    geometry = (this["geometry"] as? JsonObject)?.toFfiGeometry() ?: FfiGeometry.Empty,
    properties =
      (this["properties"] as? JsonObject)?.map { (key, value) ->
        JsonValue.Member(key, value.toFfiJsonValue())
      } ?: emptyList(),
    identifier = this["id"].toFfiFeatureIdentifier(),
  )

private fun JsonObject.toFfiGeometry(): FfiGeometry {
  val coordinates = this["coordinates"]
  return when (val type = typeName()) {
    "Point" -> FfiGeometry.Point(coordinates.toLatLng())
    "MultiPoint" -> FfiGeometry.MultiPoint(coordinates.toLatLngs())
    "LineString" -> FfiGeometry.LineString(coordinates.toLatLngs())
    "MultiLineString" -> FfiGeometry.MultiLineString(coordinates.toLatLngRings())
    "Polygon" -> FfiGeometry.Polygon(coordinates.toLatLngRings())
    "MultiPolygon" -> FfiGeometry.MultiPolygon(coordinates.asArray().map { it.toLatLngRings() })
    "GeometryCollection" ->
      FfiGeometry.Collection(this["geometries"].asArray().map { it.asObject().toFfiGeometry() })

    else -> throw IllegalArgumentException("Unsupported GeoJSON geometry type: $type")
  }
}

/**
 * A feature identifier, which RFC 7946 allows to be a string or a number. Integers keep their
 * integer form: MapLibre matches identifiers by type as well as by value.
 */
private fun JsonElement?.toFfiFeatureIdentifier(): FeatureIdentifier {
  val primitive = this as? JsonPrimitive ?: return FeatureIdentifier.Null
  if (primitive is JsonNull) return FeatureIdentifier.Null
  if (primitive.isString) return FeatureIdentifier.StringValue(primitive.content)
  primitive.longOrNull?.let {
    return FeatureIdentifier.Int(it)
  }
  primitive.content.toULongOrNull()?.let {
    // uint64_t crosses the binding as the same bits in a signed Long.
    return FeatureIdentifier.UInt(it.toLong())
  }
  primitive.doubleOrNull?.let {
    return FeatureIdentifier.DoubleValue(it)
  }
  return FeatureIdentifier.StringValue(primitive.content)
}

private fun JsonObject.typeName(): String? = (this["type"] as? JsonPrimitive)?.content

private fun JsonElement?.asObject(): JsonObject =
  this as? JsonObject ?: throw IllegalArgumentException("expected a GeoJSON object, got $this")

private fun JsonElement?.asArray(): JsonArray =
  this as? JsonArray ?: throw IllegalArgumentException("expected a GeoJSON array, got $this")

/** Reads a GeoJSON position, which is `[longitude, latitude]` and may carry a third altitude. */
private fun JsonElement?.toLatLng(): LatLng {
  val position = asArray()
  require(position.size >= 2) { "a GeoJSON position needs a longitude and a latitude" }
  return LatLng(
    latitude = position[1].jsonPrimitive.double,
    longitude = position[0].jsonPrimitive.double,
  )
}

private fun JsonElement?.toLatLngs(): List<LatLng> = asArray().map { it.toLatLng() }

private fun JsonElement?.toLatLngRings(): List<List<LatLng>> = asArray().map { it.toLatLngs() }
