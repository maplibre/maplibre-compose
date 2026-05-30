package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.ImageResizeOptions

internal class SafeStyle(private val delegate: Style) : Style {
  internal var isUnloaded = false

  internal fun unload() {
    isUnloaded = true
  }

  private fun warnIfUnloaded(methodName: String) {
    if (isUnloaded) {
      println("Warning: Attempting to call $methodName on an unloaded style")
    }
  }

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {
    warnIfUnloaded("addImage")
    if (!isUnloaded) delegate.addImage(id, image, sdf, resizeOptions)
  }

  override fun removeImage(id: String) {
    warnIfUnloaded("removeImage")
    if (!isUnloaded) delegate.removeImage(id)
  }

  override fun getSource(id: String): Source? {
    warnIfUnloaded("getSource")
    return if (!isUnloaded) delegate.getSource(id) else null
  }

  override fun getSources(): List<Source> {
    warnIfUnloaded("getSources")
    return if (!isUnloaded) delegate.getSources() else emptyList()
  }

  override fun addSource(source: Source) {
    warnIfUnloaded("addSource")
    if (!isUnloaded) delegate.addSource(source)
  }

  override fun removeSource(source: Source) {
    warnIfUnloaded("removeSource")
    if (!isUnloaded) delegate.removeSource(source)
  }

  override fun getLayer(id: String): Layer? {
    warnIfUnloaded("getLayer")
    return if (!isUnloaded) delegate.getLayer(id) else null
  }

  override fun getLayers(): List<Layer> {
    warnIfUnloaded("getLayers")
    return if (!isUnloaded) delegate.getLayers() else emptyList()
  }

  override fun addLayer(layer: Layer) {
    warnIfUnloaded("addLayer")
    if (!isUnloaded) delegate.addLayer(layer)
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    warnIfUnloaded("addLayerAbove")
    if (!isUnloaded) delegate.addLayerAbove(id, layer)
  }

  override fun addLayerBelow(id: String, layer: Layer) {
    warnIfUnloaded("addLayerBelow")
    if (!isUnloaded) delegate.addLayerBelow(id, layer)
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    warnIfUnloaded("addLayerAt")
    if (!isUnloaded) delegate.addLayerAt(index, layer)
  }

  override fun removeLayer(layer: Layer) {
    warnIfUnloaded("removeLayer")
    if (!isUnloaded) delegate.removeLayer(layer)
  }

  override fun getLayerVisibility(id: String): Boolean? {
    warnIfUnloaded("getLayerVisibility")
    return if (!isUnloaded) delegate.getLayerVisibility(id) else null
  }

  override fun getLayerMinZoom(id: String): Float? {
    warnIfUnloaded("getLayerMinZoom")
    return if (!isUnloaded) delegate.getLayerMinZoom(id) else null
  }

  override fun getLayerMaxZoom(id: String): Float? {
    warnIfUnloaded("getLayerMaxZoom")
    return if (!isUnloaded) delegate.getLayerMaxZoom(id) else null
  }

  override fun setLayerVisibility(id: String, visible: Boolean) {
    warnIfUnloaded("setLayerVisibility")
    if (!isUnloaded) delegate.setLayerVisibility(id, visible)
  }

  override fun setLayerMinZoom(id: String, minZoom: Float) {
    warnIfUnloaded("setLayerMinZoom")
    if (!isUnloaded) delegate.setLayerMinZoom(id, minZoom)
  }

  override fun setLayerMaxZoom(id: String, maxZoom: Float) {
    warnIfUnloaded("setLayerMaxZoom")
    if (!isUnloaded) delegate.setLayerMaxZoom(id, maxZoom)
  }
}
