package org.maplibre.compose.sources

import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.gljs.GeoJsonSourceData
import org.maplibre.compose.gljs.GlJsGeoJsonSource
import org.maplibre.compose.util.toFeatureCollection
import org.maplibre.compose.util.toJsValue
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** The style-spec property MapLibre stamps on a feature that stands in for a cluster. */
private const val CLUSTER_ID_PROPERTY = "cluster_id"

public actual class GeoJsonSource : Source {

  private val options: GeoJsonOptions

  private var data: JsonElement

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : super(id) {
    this.options = options
    this.data = data.toDataJson()
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", data)
    putGeoJsonOptions(options)
    // `synchronousUpdate` is deliberately absent: MapLibre GL JS parses GeoJSON in a web worker.
  }

  public actual fun setData(data: GeoJsonData) {
    this.data = data.toDataJson()
    val js = this.data.toJsValue<GeoJsonSourceData>()
    mutate { liveSource<GlJsGeoJsonSource>()?.setData(js) }
  }

  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean =
    CLUSTER_ID_PROPERTY in feature.properties.orEmpty()

  public actual suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    val source = clusterQuery(feature) ?: return NO_EXPANSION_ZOOM
    return source.source.getClusterExpansionZoom(source.clusterId).await()
  }

  public actual suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> {
    val query =
      clusterQuery(feature) ?: return FeatureCollection<Geometry, JsonObject?>(emptyList())
    return query.source.getClusterChildren(query.clusterId).await().toFeatureCollection()
  }

  public actual suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> {
    val query =
      clusterQuery(feature) ?: return FeatureCollection<Geometry, JsonObject?>(emptyList())
    return query.source
      .getClusterLeaves(
        query.clusterId,
        limit.coerceAtLeast(0).toDouble(),
        offset.coerceAtLeast(0).toDouble(),
      )
      .await()
      .toFeatureCollection()
  }

  public actual fun setFeatureState(featureId: String, state: JsonObject) {
    setJsFeatureState(featureId = featureId, state = state)
  }

  public actual fun getFeatureState(featureId: String): JsonObject = jsFeatureState(featureId)

  public actual fun removeFeatureState(featureId: String, stateKey: String?) {
    removeJsFeatureState(featureId = featureId, stateKey = stateKey)
  }

  public actual fun resetFeatureStates() {
    removeJsFeatureState()
  }

  private class ClusterQuery(val source: GlJsGeoJsonSource, val clusterId: Double)

  /** Null when the feature is not a cluster or the style has unloaded. */
  private fun clusterQuery(feature: Feature<*, JsonObject?>): ClusterQuery? {
    val clusterId =
      (feature.properties?.get(CLUSTER_ID_PROPERTY) as? JsonPrimitive)?.doubleOrNull
        ?: run {
          binding?.logger?.w {
            "Cluster query on a feature with no '$CLUSTER_ID_PROPERTY' in source '$id'"
          }
          return null
        }
    val source = liveSource<GlJsGeoJsonSource>() ?: return null
    return ClusterQuery(source, clusterId)
  }

  private companion object {
    /** Reported when the cluster has no expansion zoom to give. */
    const val NO_EXPANSION_ZOOM = 0.0
  }
}
