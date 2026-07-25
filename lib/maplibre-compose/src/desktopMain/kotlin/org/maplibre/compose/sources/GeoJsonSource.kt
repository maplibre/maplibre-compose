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
import org.maplibre.compose.util.toFfiJsonValue
import org.maplibre.compose.util.toStyleJson
import org.maplibre.nativeffi.geo.Feature as FfiFeature
import org.maplibre.nativeffi.geo.FeatureIdentifier
import org.maplibre.nativeffi.geo.GeoJson as FfiGeoJson
import org.maplibre.nativeffi.geo.Geometry as FfiGeometry
import org.maplibre.nativeffi.geo.LatLng
import org.maplibre.nativeffi.json.JsonValue
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
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
    return "cluster_id" in feature.properties.orEmpty()
  }

  public actual fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    // TODO(maplibre-native-ffi): the supercluster queries only exist as queryFeatureExtension on
    //   RenderSessionHandle, which a source cannot reach through its style binding. MapHandle needs
    //   a queryFeatureExtension taking a source id, a feature, an extension name, and a field name.
    //   Until then a cluster reports no expansion zoom, so tapping one cannot zoom into it.
    return 0.0
  }

  public actual fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> {
    // TODO(maplibre-native-ffi): see getClusterExpansionZoom.
    return FeatureCollection<Geometry, JsonObject?>(emptyList())
  }

  public actual fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> {
    // TODO(maplibre-native-ffi): see getClusterExpansionZoom.
    return FeatureCollection<Geometry, JsonObject?>(emptyList())
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
