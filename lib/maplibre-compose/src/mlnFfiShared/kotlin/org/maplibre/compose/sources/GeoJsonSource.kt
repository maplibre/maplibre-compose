@file:JvmName("MlnFfiGeoJsonSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.util.CLUSTER_ID_PROPERTY
import org.maplibre.compose.util.toFfiClusterFeature
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.style.GeoJsonSourceDataHandle
import org.maplibre.nativeffi.style.GeoJsonSourceOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

public actual class GeoJsonSource : Source {

  private val options: GeoJsonOptions

  // Held parsed because toJson runs again on every re-add after a style change.
  private var data: JsonElement

  /** The URI form of [data], when it is one. */
  private var dataUrl: String?

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : super(id) {
    this.options = options
    this.data = data.toDataJson()
    this.dataUrl = (data as? GeoJsonData.Uri)?.uri
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

  override fun addTo(map: MapHandle) {
    val url = dataUrl
    if (url != null) {
      map.addGeoJsonSourceUrl(id, url, options.toFfiOptions())
    } else {
      prepareData().use { map.addGeoJsonSourceData(id, it) }
    }
  }

  public actual fun setData(data: GeoJsonData) {
    this.data = data.toDataJson()
    this.dataUrl = (data as? GeoJsonData.Uri)?.uri
    if (data is GeoJsonData.Uri) {
      mutate { map -> map.setGeoJsonSourceUrl(id, data.uri) }
    } else {
      // Prepared outside the lambda so the map's owner thread does not parse or index the data.
      // Closed after the posted mutation: mutateMap queues the work and does not wait.
      val prepared = prepareData()
      val posted = mutate { map ->
        try {
          map.setGeoJsonSourceData(id, prepared)
        } finally {
          prepared.close()
        }
      }
      if (!posted) prepared.close()
    }
  }

  /** Prepared with the options the source was added with; a mismatch is rejected at install. */
  private fun prepareData(): GeoJsonSourceDataHandle =
    GeoJsonSourceDataHandle.create(data.toJsonBytes(), options.toFfiOptions())

  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean {
    return CLUSTER_ID_PROPERTY in feature.properties.orEmpty()
  }

  public actual suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    val result = queryClusterExtension(feature, EXPANSION_ZOOM_FIELD)
    val zoom = result?.decodeToString()?.toDoubleOrNull()
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
      // Both must be unsigned: MapLibre type-checks them exactly and silently falls back to its own
      // default of ten otherwise, and it ignores offset unless limit is present. A non-negative
      // integer literal parses as unsigned.
      // https://github.com/maplibre/maplibre-native-ffi/pull/340
      buildJsonObject {
        put("limit", limit.coerceAtLeast(0))
        put("offset", offset.coerceAtLeast(0))
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
  ): ByteArray? {
    val ffiFeature = feature.toFfiClusterFeature() ?: return null
    return binding.withRenderSession { session ->
      session.queryFeatureExtension(id, ffiFeature, SUPERCLUSTER_EXTENSION, field, arguments)
    }
  }

  private fun queryClusterFeatures(
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: ByteArray?,
  ): FeatureCollection<*, JsonObject?> {
    val result = queryClusterExtension(feature, field, arguments)
    val collection =
      result?.decodeToString()?.let { FeatureCollection.fromJsonOrNull<Geometry, JsonObject?>(it) }
    if (collection == null) {
      reportMiss(field, result)
      return FeatureCollection<Geometry, JsonObject?>(emptyList())
    }
    return collection
  }

  /**
   * Reports a lookup that found no cluster. MapLibre answers a successful query with a feature
   * collection, even an empty one, and a failed one with a null value.
   */
  private fun reportMiss(field: String, result: ByteArray?) {
    if (result == null) return
    binding.logger?.w {
      "Cluster '$field' query matched no cluster in source '$id'; the feature's cluster_id is " +
        "probably stale. MapLibre answered with ${result.decodeToString()}."
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

/** The same options [putGeoJsonOptions] writes into source JSON, as the typed adder takes them. */
private fun GeoJsonOptions.toFfiOptions(): GeoJsonSourceOptions =
  GeoJsonSourceOptions().also {
    it.minZoom = minZoom.toDouble()
    it.maxZoom = maxZoom.toDouble()
    it.tolerance = tolerance.toDouble()
    it.buffer = buffer
    it.cluster = cluster
    it.clusterRadius = clusterRadius
    it.clusterMaxZoom = clusterMaxZoom.toDouble()
    it.clusterMinPoints = clusterMinPoints
    it.lineMetrics = lineMetrics
    it.synchronousTiling = synchronousUpdate
    it.clusterProperties = clusterPropertiesBytes()
  }

private fun GeoJsonOptions.clusterPropertiesBytes(): ByteArray? {
  if (clusterProperties.isEmpty()) return null
  return buildJsonObject { putClusterProperties(clusterProperties) }.toJsonBytes()
}
