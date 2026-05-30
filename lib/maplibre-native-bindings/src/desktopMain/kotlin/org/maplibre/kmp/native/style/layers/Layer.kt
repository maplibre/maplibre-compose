package org.maplibre.kmp.native.style.layers

import org.maplibre.kmp.native.map.MapLibreMap

public open class Layer(
  public val id: String,
  public val type: String,
  public val sourceId: String?,
) {
  internal var map: MapLibreMap? = null
  private val pendingProperties = mutableMapOf<String, String>()

  public var minZoom: Float = 0f
    set(value) {
      field = value
      map?.setStyleLayerMinZoom(id, value)
    }

  public var maxZoom: Float = 24f
    set(value) {
      field = value
      map?.setStyleLayerMaxZoom(id, value)
    }

  public var visible: Boolean = true
    set(value) {
      field = value
      map?.setStyleLayerVisible(id, value)
    }

  public fun setProperty(name: String, valueJson: String) {
    val m = map
    if (m != null) {
      m.setStyleLayerProperty(id, name, valueJson)
    } else {
      pendingProperties[name] = valueJson
    }
  }

  public open fun bind(map: MapLibreMap) {
    this.map = map
    // Flush buffered state
    if (minZoom != 0f) map.setStyleLayerMinZoom(id, minZoom)
    if (maxZoom != 24f) map.setStyleLayerMaxZoom(id, maxZoom)
    if (!visible) map.setStyleLayerVisible(id, false)
    pendingProperties.forEach { (name, value) -> map.setStyleLayerProperty(id, name, value) }
    pendingProperties.clear()
  }

  public open fun unbind() {
    map = null
  }
}
