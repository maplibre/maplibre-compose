package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.ImageResizeOptions

internal class FakeStyle(
  images: List<Pair<String, ImageBitmap>>,
  sources: List<Source>,
  layers: List<Layer>,
) : Style {
  private val imageMap = images.toMap().toMutableMap()
  private val sourceMap = sources.associateBy { it.id }.toMutableMap()
  private val layerList = layers.toMutableList()
  private val layerMap = layers.associateBy { it.id }.toMutableMap()

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {
    if (id in imageMap) error("Image ID '${id}' already exists in style")
    imageMap[id] = image
  }

  override fun removeImage(id: String) {
    if (id !in imageMap) error("Image ID '${id}' not found in style")
    imageMap.remove(id)
  }

  override fun getSource(id: String): Source? = sourceMap[id]

  override fun getSources(): List<Source> = sourceMap.values.toList()

  override fun addSource(source: Source) {
    if (source.id in sourceMap) error("Source ID '${source.id}' already exists in style")
    sourceMap[source.id] = source
  }

  override fun removeSource(source: Source) {
    if (source.id !in sourceMap) error("Source ID '${source.id}' not found in style")
    sourceMap.remove(source.id)
  }

  override fun getLayer(id: String): Layer? {
    return layerMap[id]
  }

  override fun getLayers(): List<Layer> {
    return layerList.toList()
  }

  override fun addLayer(layer: Layer) {
    if (layer.id in layerMap) error("Layer ID '${layer.id}' already exists in style")
    layerList.add(layer)
    layerMap[layer.id] = layer
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    if (layer.id in layerMap) error("Layer ID '${layer.id}' already exists in style")
    val index = layerList.indexOfFirst { it.id == id }
    if (index == -1) error("Layer ID '$id' not found in base style")
    layerList.add(index + 1, layer)
    layerMap[layer.id] = layer
  }

  override fun addLayerBelow(id: String, layer: Layer) {
    if (layer.id in layerMap) error("Layer ID '${layer.id}' already exists in style")
    val index = layerList.indexOfFirst { it.id == id }
    if (index == -1) error("Layer ID '$id' not found in base style")
    layerList.add(index, layer)
    layerMap[layer.id] = layer
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    if (layer.id in layerMap) error("Layer ID '${layer.id}' already exists in style")
    layerList.add(index, layer)
    layerMap[layer.id] = layer
  }

  override fun removeLayer(layer: Layer) {
    if (layer.id !in layerMap) error("Layer ID '${layer.id}' not found in style")
    if (!layerList.remove(layer)) error("Layer '${layer}' not found in style")
    layerMap.remove(layer.id)
  }
}
