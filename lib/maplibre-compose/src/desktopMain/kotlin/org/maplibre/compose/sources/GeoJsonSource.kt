@file:JvmName("DesktopGeoJsonSourceKt")

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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.util.CLUSTER_ID_PROPERTY
import org.maplibre.compose.util.toFfiClusterFeature
import org.maplibre.compose.util.toFfiJsonValue
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.compose.util.toStyleJson
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

  // Held in its parsed form because toJson is not just an attachment step: reading attributionHtml
  // calls it, and so does every re-add after a style change.
  private var data: JsonElement

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : super(id) {
    this.options = options
    this.data = data.toDataJson()
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", data)
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
    put("buffer", options.buffer)
    put("tolerance", options.tolerance)
    put("lineMetrics", options.lineMetrics)
    put("cluster", options.cluster)
    put("clusterRadius", options.clusterRadius)
    put("clusterMaxZoom", options.clusterMaxZoom)
    put("clusterMinPoints", options.clusterMinPoints)
    // MapLibre Native reads this straight off the source JSON, so desktop honors it even though the
    // common documentation only promises it on Android.
    put("synchronousUpdate", options.synchronousUpdate)
    if (options.clusterProperties.isNotEmpty()) {
      putJsonObject("clusterProperties") {
        options.clusterProperties.forEach { (name, aggregator) ->
          // Reducer first, then mapper: the style spec's pair is [operator, map expression].
          putJsonArray(name) {
            add(aggregator.reducer.compile(ExpressionContext.None).toStyleJson())
            add(aggregator.mapper.compile(ExpressionContext.None).toStyleJson())
          }
        }
      }
    }
  }

  public actual fun setData(data: GeoJsonData) {
    this.data = data.toDataJson()
    if (data is GeoJsonData.Uri) {
      mutate { map -> map.setGeoJsonSourceUrl(id, data.uri) }
    } else {
      // Converted here rather than inside the lambda, so the map's owner thread does not walk the
      // data.
      val geoJson = this.data.toFfiGeoJson()
      mutate { map -> map.setGeoJsonSourceData(id, geoJson) }
    }
  }

  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean {
    return CLUSTER_ID_PROPERTY in feature.properties.orEmpty()
  }

  public actual fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    val result = queryClusterExtension(feature, EXPANSION_ZOOM_FIELD)
    val value = (result as? FeatureExtensionResult.Value)?.value
    return when (value) {
      // MapLibre computes the zoom as a uint64_t, but the other numeric shapes are accepted so a
      // future encoding change degrades instead of silently reporting the whole world.
      is JsonValue.UInt -> value.value.toULong().toDouble()
      is JsonValue.Int -> value.value.toDouble()
      is JsonValue.DoubleValue -> value.value
      else -> {
        reportMiss(EXPANSION_ZOOM_FIELD, result)
        NO_EXPANSION_ZOOM
      }
    }
  }

  public actual fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> = queryClusterFeatures(feature, CHILDREN_FIELD, null)

  public actual fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> =
    queryClusterFeatures(
      feature,
      LEAVES_FIELD,
      // Both must be unsigned, for the same reason cluster_id must be: MapLibre reads them with an
      // exact type check against the stored variant. A signed limit is not rejected — it is
      // ignored, and MapLibre quietly substitutes its own default of ten. It also ignores offset
      // unless limit is present, so both are always sent. Documented upstream by
      // https://github.com/maplibre/maplibre-native-ffi/pull/340.
      JsonValue.ObjectValue(
        listOf(
          JsonValue.Member("limit", JsonValue.UInt(limit.coerceAtLeast(0))),
          JsonValue.Member("offset", JsonValue.UInt(offset.coerceAtLeast(0))),
        )
      ),
    )

  /**
   * Runs one supercluster query against the render session.
   *
   * The feature is converted before the owner hop, matching [setData] above: the map's owner thread
   * should not be walking caller data while a frame waits on it.
   *
   * Returns null when the feature carries no cluster id, when no render session is attached yet, or
   * when the query failed — all cases a caller turns into the same empty answer.
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
   * Reports a lookup that found no cluster, as distinct from a cluster with nothing to report.
   *
   * MapLibre answers a successful `children`/`leaves` query with a feature collection, even an
   * empty one, and a *failed* one with a null value — so the two are distinguishable, and silently
   * folding them together is how a mistyped `cluster_id` looks exactly like a childless cluster.
   * Null is only reached with a cluster id that no longer exists in the source, which usually means
   * the feature outlived the tile it came from.
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

    /**
     * Reported when the cluster has no expansion zoom to give.
     *
     * Matches Android. Callers should compare against the current zoom rather than animating to
     * this blindly, since zooming to it would show the whole world.
     */
    const val NO_EXPANSION_ZOOM = 0.0
  }
}

/**
 * The `data` member of a GeoJSON source, which the style spec allows to be either a URL string or
 * an inline GeoJSON object.
 *
 * In-memory data goes through its serialized form so that features carrying typed properties are
 * encoded by the serializer SpatialK picks for them at runtime, which is the same route the Android
 * and iOS actuals take.
 */
private fun GeoJsonData.toDataJson(): JsonElement =
  when (this) {
    is GeoJsonData.Uri -> JsonPrimitive(uri)
    is GeoJsonData.JsonString -> Json.parseToJsonElement(json)
    is GeoJsonData.Features -> Json.parseToJsonElement(geoJson.toJson())
  }

/**
 * Converts parsed GeoJSON into the FFI's geometry tree.
 *
 * Updating an attached source has no JSON entry point — `setGeoJsonSourceData` takes the typed tree
 * — so the data has to be walked rather than handed over as it arrived.
 */
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
 * A feature identifier, which RFC 7946 allows to be a string or a number.
 *
 * Integers keep their integer form: MapLibre matches identifiers by type as well as by value, so a
 * feature whose id arrives as 5.0 does not match a `["==", ["id"], 5]` filter.
 */
private fun JsonElement?.toFfiFeatureIdentifier(): FeatureIdentifier {
  val primitive = this as? JsonPrimitive ?: return FeatureIdentifier.Null
  if (primitive is JsonNull) return FeatureIdentifier.Null
  if (primitive.isString) return FeatureIdentifier.StringValue(primitive.content)
  primitive.longOrNull?.let {
    return FeatureIdentifier.Int(it)
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
