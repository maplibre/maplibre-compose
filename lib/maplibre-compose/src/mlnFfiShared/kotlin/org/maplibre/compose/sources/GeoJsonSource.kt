@file:JvmName("MlnFfiGeoJsonSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.util.CLUSTER_ID_PROPERTY
import org.maplibre.compose.util.toFfiClusterFeature
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.compose.util.toJsonElement
import org.maplibre.compose.util.toStyleJson
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.style.GeoJsonSourceOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.toJson

public actual class GeoJsonSource : Source {

  private val options: GeoJsonOptions
  private var data: GeoJsonData

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : super(id) {
    this.options = options
    this.data = data
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", data.toDataJson())
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
    put("buffer", options.buffer)
    put("tolerance", options.tolerance)
    put("lineMetrics", options.lineMetrics)
    put("cluster", options.cluster)
    put("clusterRadius", options.clusterRadius)
    put("clusterMaxZoom", options.clusterMaxZoom)
    put("clusterMinPoints", options.clusterMinPoints)
    put("synchronousUpdate", options.synchronousUpdate)
    if (options.clusterProperties.isNotEmpty()) {
      put("clusterProperties", options.clusterPropertiesJson())
    }
  }

  /** Adds inline data through the dedicated buffer API, which avoids a Kotlin JSON object tree. */
  override fun addTo(map: MapHandle) {
    val ffiOptions = options.toFfiOptions()
    when (val current = data) {
      is GeoJsonData.Uri -> map.addGeoJsonSourceUrl(id, current.uri, ffiOptions)
      else -> map.addGeoJsonSourceData(id, current.toGeoJsonBytes(), ffiOptions)
    }
  }

  public actual fun setData(data: GeoJsonData) {
    this.data = data
    when (data) {
      is GeoJsonData.Uri -> mutate { map -> map.setGeoJsonSourceUrl(id, data.uri) }
      else -> {
        val bytes = data.toGeoJsonBytes()
        mutate { map -> map.setGeoJsonSourceData(id, bytes) }
      }
    }
  }

  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean =
    CLUSTER_ID_PROPERTY in feature.properties.orEmpty()

  public actual fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    val result = queryClusterExtension(feature, EXPANSION_ZOOM_FIELD)
    val zoom = (result?.toJsonElement() as? JsonPrimitive)?.doubleOrNull
    if (zoom != null) return zoom
    reportMiss(EXPANSION_ZOOM_FIELD, result)
    return NO_EXPANSION_ZOOM
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
      buildJsonObject {
        put("limit", limit.coerceAtLeast(0))
        put("offset", offset.coerceAtLeast(0))
      },
    )

  /**
   * Runs one supercluster query against the render session. Returns null when the feature carries
   * no cluster id or when no render session is attached.
   */
  private fun queryClusterExtension(
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: JsonObject? = null,
  ): ByteArray? {
    val ffiFeature = feature.toFfiClusterFeature() ?: return null
    return binding.withRenderSession { session ->
      session.queryFeatureExtension(
        id,
        ffiFeature,
        SUPERCLUSTER_EXTENSION,
        field,
        arguments?.toJsonBytes(),
      )
    }
  }

  private fun queryClusterFeatures(
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: JsonObject?,
  ): FeatureCollection<*, JsonObject?> {
    val result = queryClusterExtension(feature, field, arguments)
    val collection = result?.toJsonElement() as? JsonObject
    val values = collection?.get("features") as? JsonArray
    if (collection?.get("type")?.jsonPrimitive?.content != "FeatureCollection" || values == null) {
      reportMiss(field, result)
      return FeatureCollection<Geometry, JsonObject?>(emptyList())
    }
    val features: List<Feature<Geometry, JsonObject?>> = values.map { featureJson ->
      Feature.fromJson(featureJson.toString())
    }
    return FeatureCollection(features)
  }

  /** Reports a successful query that matched no cluster. */
  private fun reportMiss(field: String, result: ByteArray?) {
    if (result == null) return
    binding.logger?.w {
      "Cluster '$field' query matched no cluster in source '$id'; the feature's cluster_id is " +
        "probably stale. MapLibre answered with ${result.decodeToString()}."
    }
  }

  private companion object {
    const val SUPERCLUSTER_EXTENSION = "supercluster"
    const val EXPANSION_ZOOM_FIELD = "expansion-zoom"
    const val CHILDREN_FIELD = "children"
    const val LEAVES_FIELD = "leaves"
    const val NO_EXPANSION_ZOOM = 0.0
  }
}

private fun GeoJsonOptions.clusterPropertiesJson(): JsonObject = buildJsonObject {
  clusterProperties.forEach { (name, aggregator) ->
    putJsonArray(name) {
      add(aggregator.reducer.compile(ExpressionContext.None).toStyleJson())
      add(aggregator.mapper.compile(ExpressionContext.None).toStyleJson())
    }
  }
}

private fun GeoJsonOptions.toFfiOptions(): GeoJsonSourceOptions =
  GeoJsonSourceOptions().also {
    it.minZoom = minZoom.toDouble()
    it.maxZoom = maxZoom.toDouble()
    it.buffer = buffer
    it.tolerance = tolerance.toDouble()
    it.cluster = cluster
    it.clusterRadius = clusterRadius
    it.clusterMaxZoom = clusterMaxZoom.toDouble()
    it.clusterMinPoints = clusterMinPoints
    it.clusterProperties =
      clusterProperties
        .takeIf { values -> values.isNotEmpty() }
        ?.let {
          clusterPropertiesJson().toJsonBytes()
        }
    it.lineMetrics = lineMetrics
    it.synchronousUpdate = synchronousUpdate
  }

/** Converts a feature collection to the UTF-8 GeoJSON buffer expected by the FFI. */
internal fun FeatureCollection<*, *>.toFfiGeoJson(): ByteArray = toJson().encodeToByteArray()

private fun GeoJsonData.toGeoJsonBytes(): ByteArray =
  when (this) {
    is GeoJsonData.Uri -> error("A GeoJSON URI has no inline data")
    is GeoJsonData.JsonString -> json.encodeToByteArray()
    is GeoJsonData.Features -> geoJson.toJson().encodeToByteArray()
  }

private fun GeoJsonData.toDataJson(): JsonElement =
  when (this) {
    is GeoJsonData.Uri -> JsonPrimitive(uri)
    is GeoJsonData.JsonString -> Json.parseToJsonElement(json)
    is GeoJsonData.Features -> Json.parseToJsonElement(geoJson.toJson())
  }
