package org.maplibre.kmp.native.style.layers

public abstract class FeatureLayer(id: String, type: String, sourceId: String) :
  Layer(id, type, sourceId) {

  private var pendingSourceLayer: String? = null
  private var pendingFilter: String? = null

  public var sourceLayer: String = ""
    set(value) {
      field = value
      val m = map
      if (m != null) {
        m.setStyleLayerSourceLayer(id, value)
      } else {
        pendingSourceLayer = value
      }
    }

  public fun setFilter(filterJson: String) {
    val m = map
    if (m != null) {
      m.setStyleLayerFilter(id, filterJson)
    } else {
      pendingFilter = filterJson
    }
  }

  override fun bind(map: org.maplibre.kmp.native.map.MapLibreMap) {
    super.bind(map)
    pendingSourceLayer?.let {
      map.setStyleLayerSourceLayer(id, it)
      pendingSourceLayer = null
    }
    pendingFilter?.let {
      map.setStyleLayerFilter(id, it)
      pendingFilter = null
    }
  }
}
