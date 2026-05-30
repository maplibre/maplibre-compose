package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.UnknownSource
import org.maplibre.compose.util.ImageResizeOptions
import org.maplibre.kmp.native.map.MapLibreMap

internal class DesktopStyle(internal val impl: MapLibreMap) : Style {

  private val layerCache = mutableMapOf<String, Layer>()
  private val layerOrder = mutableListOf<String>()
  private val sourceCache = mutableMapOf<String, Source>()

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {
    val width = image.width
    val height = image.height
    val pixelMap = image.toPixelMap()
    val data = ByteArray(width * height * 4)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val color = pixelMap[x, y]
        val a = color.alpha
        val offset = (y * width + x) * 4
        // Premultiply alpha for mbgl::PremultipliedImage
        data[offset] = (color.red * a * 255f).toInt().toByte()
        data[offset + 1] = (color.green * a * 255f).toInt().toByte()
        data[offset + 2] = (color.blue * a * 255f).toInt().toByte()
        data[offset + 3] = (a * 255f).toInt().toByte()
      }
    }
    if (resizeOptions == null) {
      impl.addStyleImage(id, width, height, 1.0f, sdf, data)
    } else {
      // Dp values treated as px at ratio 1.0 (desktop default density)
      val left = resizeOptions.left.value
      val top = resizeOptions.top.value
      val right = width - resizeOptions.right.value
      val bottom = height - resizeOptions.bottom.value
      impl.addStyleImageStretched(
        id,
        width,
        height,
        1.0f,
        sdf,
        data,
        stretchXFrom = left,
        stretchXTo = right,
        stretchYFrom = top,
        stretchYTo = bottom,
        contentLeft = left,
        contentTop = top,
        contentRight = right,
        contentBottom = bottom,
      )
    }
  }

  override fun removeImage(id: String) {
    impl.removeStyleImage(id)
  }

  override fun getSource(id: String): Source? {
    sourceCache[id]?.let {
      return it
    }
    // Check native style for base sources
    if (impl.hasStyleSource(id)) {
      val mlnSource = org.maplibre.kmp.native.style.sources.Source(id, "unknown")
      mlnSource.bind(impl)
      return UnknownSource(mlnSource)
    }
    return null
  }

  override fun getSources(): List<Source> {
    val nativeIds = impl.getStyleSourceIds()
    return nativeIds.map { id ->
      sourceCache[id]
        ?: run {
          val mlnSource = org.maplibre.kmp.native.style.sources.Source(id, "unknown")
          mlnSource.bind(impl)
          UnknownSource(mlnSource)
        }
    }
  }

  override fun addSource(source: Source) {
    impl.addStyleSource(source.impl.type, source.impl.id, source.impl.configJson())
    source.impl.bind(impl)
    sourceCache[source.id] = source
  }

  override fun removeSource(source: Source) {
    impl.removeStyleSource(source.impl.id)
    source.impl.unbind()
    sourceCache.remove(source.id)
  }

  override fun getLayer(id: String): Layer? {
    layerCache[id]?.let {
      return it
    }
    // Check native style for base layers
    if (impl.hasStyleLayer(id)) {
      val mlnLayer = org.maplibre.kmp.native.style.layers.Layer(id, "unknown", null)
      mlnLayer.bind(impl)
      val layer = UnknownLayer(mlnLayer)
      layerCache[id] = layer
      return layer
    }
    return null
  }

  override fun getLayers(): List<Layer> {
    // Return base style layers (from native) + user layers, in native order
    val nativeIds = impl.getStyleLayerIds()
    return nativeIds.map { id ->
      layerCache[id]
        ?: run {
          val mlnLayer = org.maplibre.kmp.native.style.layers.Layer(id, "unknown", null)
          mlnLayer.bind(impl)
          val layer = UnknownLayer(mlnLayer)
          layerCache[id] = layer
          layer
        }
    }
  }

  private fun insertLayer(layer: Layer, beforeId: String?) {
    if (layer.impl.type == "unknown") {
      // Restore a previously-removed base style layer
      impl.restoreStyleLayer(layer.impl.id, beforeId)
    } else {
      impl.addStyleLayer(layer.impl.type, layer.impl.id, layer.impl.sourceId, beforeId)
    }
    layer.impl.bind(impl)
    layerCache[layer.id] = layer
    if (beforeId != null) {
      val idx = layerOrder.indexOf(beforeId)
      if (idx >= 0) layerOrder.add(idx, layer.id) else layerOrder.add(layer.id)
    } else {
      layerOrder.add(layer.id)
    }
  }

  override fun addLayer(layer: Layer) = insertLayer(layer, beforeId = null)

  override fun addLayerAbove(id: String, layer: Layer) {
    // Find the layer after 'id' in the native layer order
    val nativeIds = impl.getStyleLayerIds()
    val idx = nativeIds.indexOf(id)
    if (idx < 0) error("Anchor layer '$id' not found in style")
    val beforeId = if (idx + 1 < nativeIds.size) nativeIds[idx + 1] else null
    insertLayer(layer, beforeId)
  }

  override fun addLayerBelow(id: String, layer: Layer) = insertLayer(layer, beforeId = id)

  override fun addLayerAt(index: Int, layer: Layer) {
    // Use native layer order to find the correct beforeId
    val nativeIds = impl.getStyleLayerIds()
    val beforeId = nativeIds.getOrNull(index)
    insertLayer(layer, beforeId)
  }

  override fun removeLayer(layer: Layer) {
    impl.removeStyleLayer(layer.impl.id)
    layer.impl.unbind()
    layerCache.remove(layer.id)
    layerOrder.remove(layer.id)
  }
}
