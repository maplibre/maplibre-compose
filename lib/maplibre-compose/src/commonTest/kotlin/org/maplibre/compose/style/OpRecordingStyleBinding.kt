package org.maplibre.compose.style

import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source

/** Records the order of structural style mutations, in place of an engine. */
internal open class OpRecordingStyleBinding(
  baseSources: List<Source> = emptyList(),
  baseLayers: List<Layer> = emptyList(),
) : RecordingStyleBinding(baseSources = baseSources, baseLayers = baseLayers) {
  val ops: MutableList<String> = mutableListOf()

  protected open fun op(name: String) {
    ops.add(name)
  }

  override fun addSource(source: Source): Boolean {
    op("addSource:${source.id}")
    return super.addSource(source)
  }

  override fun removeSource(source: Source) {
    op("removeSource:${source.id}")
    super.removeSource(source)
  }

  override fun addLayer(layer: Layer) {
    op("addLayer:${layer.id}")
    super.addLayer(layer)
  }

  override fun addLayerAbove(layerId: String, layer: Layer) {
    op("addLayerAbove:${layer.id}")
    super.addLayerAbove(layerId, layer)
  }

  override fun addLayerBelow(layerId: String, layer: Layer) {
    op("addLayerBelow:${layer.id}")
    super.addLayerBelow(layerId, layer)
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    op("addLayerAt:${layer.id}")
    super.addLayerAt(index, layer)
  }

  override fun removeLayer(layer: Layer) {
    op("removeLayer:${layer.id}")
    super.removeLayer(layer)
  }

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    op("moveLayer:$layerId")
    super.moveLayer(layerId, beforeLayerId)
  }
}
