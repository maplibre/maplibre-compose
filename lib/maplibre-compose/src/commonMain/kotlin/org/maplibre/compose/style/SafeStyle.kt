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

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {
    if (!isUnloaded) delegate.addImage(id, image, sdf, resizeOptions)
  }

  override fun removeImage(id: String) {
    if (!isUnloaded) delegate.removeImage(id)
  }

  override fun getSource(id: String): Source? {
    return if (!isUnloaded) delegate.getSource(id) else null
  }

  override fun getSources(): List<Source> {
    return if (!isUnloaded) delegate.getSources() else emptyList()
  }

  override fun addSource(source: Source) {
    if (!isUnloaded) delegate.addSource(source)
  }

  override fun removeSource(source: Source) {
    if (!isUnloaded) delegate.removeSource(source)
  }

  override fun getLayer(id: String): Layer? {
    return if (!isUnloaded) delegate.getLayer(id) else null
  }

  override fun getLayers(): List<Layer> {
    return if (!isUnloaded) delegate.getLayers() else emptyList()
  }

  override fun addLayer(layer: Layer) {
    if (!isUnloaded) delegate.addLayer(layer)
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    if (!isUnloaded) delegate.addLayerAbove(id, layer)
  }

  override fun addLayerBelow(id: String, layer: Layer) {
    if (!isUnloaded) delegate.addLayerBelow(id, layer)
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    if (!isUnloaded) delegate.addLayerAt(index, layer)
  }

  override fun removeLayer(layer: Layer) {
    if (!isUnloaded) delegate.removeLayer(layer)
  }
}
