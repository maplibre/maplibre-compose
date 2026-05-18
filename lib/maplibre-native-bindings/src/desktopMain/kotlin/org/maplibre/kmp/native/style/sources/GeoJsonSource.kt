package org.maplibre.kmp.native.style.sources

import org.maplibre.kmp.native.map.MapLibreMap

public class GeoJsonSource(id: String, public val optionsJson: String? = null) :
  Source(id, "geojson") {

  private var pendingGeoJson: String? = null
  private var pendingUrl: String? = null

  override fun configJson(): String? = optionsJson

  public fun setGeoJson(geoJson: String) {
    val m = map
    if (m != null) {
      m.setStyleGeoJsonSourceData(id, geoJson)
    } else {
      pendingGeoJson = geoJson
      pendingUrl = null
    }
  }

  public fun setUrl(url: String) {
    val m = map
    if (m != null) {
      m.setStyleGeoJsonSourceUrl(id, url)
    } else {
      pendingUrl = url
      pendingGeoJson = null
    }
  }

  public fun getClusterChildren(clusterId: Long): String {
    val m = map ?: return EMPTY_FEATURE_COLLECTION
    return m.getClusterChildren(id, clusterId)
  }

  public fun getClusterLeaves(clusterId: Long, limit: Long, offset: Long): String {
    val m = map ?: return EMPTY_FEATURE_COLLECTION
    return m.getClusterLeaves(id, clusterId, limit, offset)
  }

  public fun getClusterExpansionZoom(clusterId: Long): Int {
    val m = map ?: return 0
    return m.getClusterExpansionZoom(id, clusterId)
  }

  override fun bind(map: MapLibreMap) {
    super.bind(map)
    pendingGeoJson?.let {
      map.setStyleGeoJsonSourceData(id, it)
      pendingGeoJson = null
    }
    pendingUrl?.let {
      map.setStyleGeoJsonSourceUrl(id, it)
      pendingUrl = null
    }
  }

  private companion object {
    const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
  }
}
