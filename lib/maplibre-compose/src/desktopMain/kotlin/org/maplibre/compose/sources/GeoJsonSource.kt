package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.maplibre.kmp.native.style.sources.GeoJsonSource as MLNGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.toJson

public actual class GeoJsonSource : Source {

  override val impl: MLNGeoJsonSource

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : super() {
    impl = MLNGeoJsonSource(id, buildOptionsJson(options))
    setData(data)
  }

  public actual fun setData(data: GeoJsonData) {
    when (data) {
      is GeoJsonData.Features -> impl.setGeoJson(data.geoJson.toJson())
      is GeoJsonData.JsonString -> impl.setGeoJson(data.json)
      is GeoJsonData.Uri -> impl.setUrl(data.uri)
    }
  }

  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean {
    return "cluster_id" in feature.properties.orEmpty()
  }

  public actual fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    val clusterId = extractClusterId(feature) ?: return 0.0
    return impl.getClusterExpansionZoom(clusterId).toDouble()
  }

  public actual fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> {
    val clusterId = extractClusterId(feature) ?: return FeatureCollection<Geometry?, JsonObject?>()
    val json = impl.getClusterChildren(clusterId)
    return try {
      val fc: FeatureCollection<Geometry?, JsonObject?> = FeatureCollection.fromJson(json)
      fc
    } catch (_: Exception) {
      FeatureCollection<Geometry?, JsonObject?>()
    }
  }

  public actual fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> {
    val clusterId = extractClusterId(feature) ?: return FeatureCollection<Geometry?, JsonObject?>()
    val json = impl.getClusterLeaves(clusterId, limit, offset)
    return try {
      val fc: FeatureCollection<Geometry?, JsonObject?> = FeatureCollection.fromJson(json)
      fc
    } catch (_: Exception) {
      FeatureCollection<Geometry?, JsonObject?>()
    }
  }

  private fun extractClusterId(feature: Feature<*, JsonObject?>): Long? {
    return feature.properties?.get("cluster_id")?.jsonPrimitive?.longOrNull
  }

  private companion object {
    fun buildOptionsJson(options: GeoJsonOptions): String? {
      if (options == GeoJsonOptions()) return null
      return buildJsonObject {
          put("minzoom", options.minZoom)
          put("maxzoom", options.maxZoom)
          put("buffer", options.buffer)
          put("tolerance", options.tolerance)
          put("cluster", options.cluster)
          put("clusterRadius", options.clusterRadius)
          put("clusterMaxZoom", options.clusterMaxZoom)
          put("clusterMinPoints", options.clusterMinPoints)
          put("lineMetrics", options.lineMetrics)
        }
        .toString()
    }
  }
}
