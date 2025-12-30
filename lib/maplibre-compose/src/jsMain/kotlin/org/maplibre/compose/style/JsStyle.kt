package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.ImageResizeOptions
import org.maplibre.kmp.js.map.Map

internal class JsStyle(internal val impl: Map) : Style {

  private val sourceCache = mutableMapOf<String, Source>()

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {}

  override fun removeImage(id: String) {}

  override fun getSource(id: String): Source? {
    return sourceCache[id]
  }

  override fun getSources(): List<Source> {
    return sourceCache.values.toList()
  }

  override fun addSource(source: Source) {
    impl.addSource(source.id, source.spec)
    impl.getSource(source.id)?.let { source.bind(it) }
    sourceCache[source.id] = source
  }

  override fun removeSource(source: Source) {
    impl.removeSource(source.id)
    sourceCache.remove(source.id)
  }

  override fun getLayer(id: String): Layer? {
    return null
  }

  override fun getLayers(): List<Layer> {
    return emptyList()
  }

  override fun addLayer(layer: Layer) {}

  override fun addLayerAbove(id: String, layer: Layer) {}

  override fun addLayerBelow(id: String, layer: Layer) {}

  override fun addLayerAt(index: Int, layer: Layer) {}

  override fun removeLayer(layer: Layer) {}
}
