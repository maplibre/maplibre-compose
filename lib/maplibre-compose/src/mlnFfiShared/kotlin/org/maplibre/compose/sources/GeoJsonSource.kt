@file:JvmName("MlnFfiGeoJsonSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
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
  private val ffiOptions: GeoJsonSourceOptions
  private lateinit var data: GeoJsonData

  /**
   * UTF-8 GeoJSON for inline data, encoded when the data is set so attach and [setData] reuse it.
   * Null when [data] is a URL.
   */
  private var inlineUtf8: ByteArray? = null

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : super(id) {
    this.options = options
    this.ffiOptions = options.toFfiOptions()
    replaceData(data)
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", data.toDataJson())
    putGeoJsonOptions(options)
    // Neither is in the style spec's GeoJSON source, but MapLibre Native reads both straight off
    // the source JSON.
    put("minzoom", options.minZoom)
    put("synchronousUpdate", options.synchronousUpdate)
  }

  /** Adds with a URL or UTF-8 GeoJSON. Native parses that payload once. */
  override fun addTo(map: MapHandle) {
    val bytes = inlineUtf8
    if (bytes != null) map.addGeoJsonSourceData(id, bytes, ffiOptions)
    else map.addGeoJsonSourceUrl(id, (data as GeoJsonData.Uri).uri, ffiOptions)
  }

  public actual fun setData(data: GeoJsonData) {
    replaceData(data)
    val bytes = inlineUtf8
    if (bytes != null) mutate { map -> map.setGeoJsonSourceData(id, bytes) }
    else mutate { map -> map.setGeoJsonSourceUrl(id, (data as GeoJsonData.Uri).uri) }
  }

  private fun replaceData(data: GeoJsonData) {
    this.data = data
    inlineUtf8 = data.inlineUtf8()
  }

  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean {
    return CLUSTER_ID_PROPERTY in feature.properties.orEmpty()
  }

  public actual suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    val result = queryClusterExtension(feature, EXPANSION_ZOOM_FIELD)
    val zoom = (result as? JsonPrimitive)?.doubleOrNull
    if (zoom == null) {
      reportMiss(EXPANSION_ZOOM_FIELD, result)
      return NO_EXPANSION_ZOOM
    }
    return zoom
  }

  public actual suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> = queryClusterFeatures(feature, CHILDREN_FIELD, null)

  public actual fun setFeatureState(featureId: String, state: JsonObject) {
    binding.setFeatureState(id, featureId, state)
  }

  public actual fun getFeatureState(featureId: String): JsonObject =
    binding.getFeatureState(id, featureId)

  public actual fun removeFeatureState(featureId: String, stateKey: String?) {
    binding.removeFeatureState(id, featureId, stateKey)
  }

  public actual fun resetFeatureStates() {
    binding.resetFeatureStates(id)
  }

  public actual suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> =
    queryClusterFeatures(
      feature,
      LEAVES_FIELD,
      // Both must be unsigned integers in the JSON MapLibre type-checks; a signed or floating
      // value silently falls back to its own default of ten, and it ignores offset unless limit is
      // present.
      // https://github.com/maplibre/maplibre-native-ffi/pull/340
      buildJsonObject {
        put("limit", JsonPrimitive(limit.coerceAtLeast(0).toULong()))
        put("offset", JsonPrimitive(offset.coerceAtLeast(0).toULong()))
      }
        .toJsonBytes(),
    )

  /**
   * Runs one supercluster query against the render session. Returns null when the feature carries
   * no cluster id, when no render session is attached yet, or when the query failed.
   */
  private fun queryClusterExtension(
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: ByteArray? = null,
  ): JsonElement? {
    val ffiFeature = feature.toFfiClusterFeature() ?: return null
    val bytes =
      binding.withRenderSession { session ->
        session.queryFeatureExtension(id, ffiFeature, SUPERCLUSTER_EXTENSION, field, arguments)
      } ?: return null
    if (bytes.isEmpty()) return null
    return runCatching { bytes.toJsonElement() }.getOrNull()
  }

  private fun queryClusterFeatures(
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: ByteArray?,
  ): FeatureCollection<*, JsonObject?> {
    val result = queryClusterExtension(feature, field, arguments)
    val features = (result as? JsonObject)?.toFeatureList()
    if (features == null) {
      reportMiss(field, result)
      return FeatureCollection<Geometry, JsonObject?>(emptyList())
    }
    return FeatureCollection(features)
  }

  /**
   * Reports a lookup that found no cluster. MapLibre answers a successful query with a feature
   * collection, even an empty one, and a failed one with a null value.
   */
  private fun reportMiss(field: String, result: JsonElement?) {
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

/** Encodes caller-supplied features as UTF-8 GeoJSON for the FFI buffer API. */
internal fun FeatureCollection<*, *>.toFfiGeoJson(): ByteArray = toJson().encodeToByteArray()

private fun GeoJsonData.inlineUtf8(): ByteArray? =
  when (this) {
    is GeoJsonData.Uri -> null
    is GeoJsonData.JsonString -> json.encodeToByteArray()
    is GeoJsonData.Features -> geoJson.toJson().encodeToByteArray()
  }

private fun GeoJsonOptions.toFfiOptions(): GeoJsonSourceOptions =
  GeoJsonSourceOptions().also { out ->
    out.minZoom = minZoom.toDouble()
    out.maxZoom = maxZoom.toDouble()
    out.buffer = buffer
    out.tolerance = tolerance.toDouble()
    out.lineMetrics = lineMetrics
    out.cluster = cluster
    out.clusterRadius = clusterRadius
    out.clusterMaxZoom = clusterMaxZoom.toDouble()
    out.clusterMinPoints = clusterMinPoints
    out.synchronousUpdate = synchronousUpdate
    if (clusterProperties.isEmpty()) return@also
    out.clusterProperties =
      buildJsonObject {
        clusterProperties.forEach { (name, aggregator) ->
          putJsonArray(name) {
            add(aggregator.reducer.compile(ExpressionContext.None).toStyleJson())
            add(aggregator.mapper.compile(ExpressionContext.None).toStyleJson())
          }
        }
      }
        .toJsonBytes()
  }

private fun JsonObject.toFeatureList(): List<Feature<Geometry, JsonObject?>>? {
  if ((this["type"] as? JsonPrimitive)?.content != "FeatureCollection") return null
  val features = this["features"] as? JsonArray ?: return emptyList()
  return features.map { Feature.fromJson(it.toString()) }
}
