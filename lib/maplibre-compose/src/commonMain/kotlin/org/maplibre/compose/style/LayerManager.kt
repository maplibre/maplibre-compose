package org.maplibre.compose.style

import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer

internal class LayerManager(private val styleNode: StyleNode) {
  private val baseLayers = styleNode.style.getLayers().associateBy { it.id }

  private val userLayers = mutableListOf<LayerNode<*>>()

  // special handling for Replace anchors
  private val replacedLayers = mutableMapOf<Anchor.Replace, Layer>()
  private val replacementCounters = mutableMapOf<Anchor.Replace, Int>()

  internal fun addLayer(node: LayerNode<*>, index: Int) {
    require(node.layer.id !in baseLayers) {
      "Layer ID '${node.layer.id}' already exists in base style"
    }
    node.anchor.validate()
    styleNode.logger?.i {
      "Queuing layer ${node.layer.id} for addition at anchor ${node.anchor}, index $index"
    }
    userLayers.add(index, node)
  }

  internal fun removeLayer(node: LayerNode<*>, oldIndex: Int) {
    userLayers.removeAt(oldIndex)

    // special handling for Replace anchors
    // restore the original before removing if this layer was the last replacement
    val anchor = node.anchor
    if (anchor is Anchor.Replace) {
      val count = replacementCounters.getValue(anchor) - 1
      if (count > 0) replacementCounters[anchor] = count
      else {
        replacementCounters.remove(anchor)
        styleNode.logger?.i { "Restoring layer ${anchor.layerId}" }
        styleNode.style.addLayerBelow(node.layer.id, replacedLayers.remove(anchor)!!)
      }
    }

    styleNode.logger?.i { "Removing layer ${node.layer.id}" }
    styleNode.style.removeLayer(node.layer)
    node.added = false
  }

  internal fun moveLayer(node: LayerNode<*>, oldIndex: Int, index: Int) {
    styleNode.logger?.i { "Moving layer ${node.layer.id} from $oldIndex to $index" }
    removeLayer(node, oldIndex)
    addLayer(node, index)
    styleNode.logger?.i { "Done moving layer ${node.layer.id}" }
  }

  internal fun applyChanges() {
    val tailLayerIds = mutableMapOf<Anchor, String>()
    val missedLayers = mutableMapOf<Anchor, MutableList<LayerNode<*>>>()

    userLayers.forEach { node ->
      val layer = node.layer
      val anchor = node.anchor

      if (node.added && anchor in missedLayers) {
        // we found an existing head; let's add the missed layers
        val layersToAdd = missedLayers.remove(anchor)!!
        layersToAdd.forEach { missedLayer ->
          styleNode.logger?.i { "Adding layer ${missedLayer.layer.id} below ${layer.id}" }
          styleNode.style.addLayerBelow(layer.id, missedLayer.layer)
          missedLayer.markAdded()
        }
      }

      if (!node.added) {
        // we found a layer to add; let's try to add it, or queue it up until we find a head
        tailLayerIds[anchor]?.let { tailLayerId ->
          styleNode.logger?.i { "Adding layer ${layer.id} below $tailLayerId" }
          styleNode.style.addLayerAbove(tailLayerId, layer)
          node.markAdded()
        } ?: missedLayers.getOrPut(anchor) { mutableListOf() }.add(node)
      }

      // update the tail
      if (node.added) tailLayerIds[anchor] = layer.id
    }

    // anything left in missedLayers is a new anchor
    missedLayers.forEach { (anchor, nodes) ->
      // let's initialize the anchor with one layer
      val tail = nodes.removeAt(nodes.size - 1)
      styleNode.logger?.i { "Initializing anchor $anchor with layer ${tail.layer.id}" }
      when (anchor) {
        is Anchor.Top -> styleNode.style.addLayer(tail.layer)
        is Anchor.Bottom -> styleNode.style.addLayerAt(0, tail.layer)
        is Anchor.Above -> styleNode.style.addLayerAbove(anchor.layerId, tail.layer)
        is Anchor.Below -> styleNode.style.addLayerBelow(anchor.layerId, tail.layer)
        is Anchor.Replace -> {
          val layerToReplace = styleNode.style.getLayer(anchor.layerId)!!
          styleNode.style.addLayerAbove(layerToReplace.id, tail.layer)
          styleNode.logger?.i { "Replacing layer ${layerToReplace.id} with ${tail.layer.id}" }
          styleNode.style.removeLayer(layerToReplace)
          replacedLayers[anchor] = layerToReplace
          replacementCounters[anchor] = 0
        }
      }
      tail.markAdded()

      // and add the rest below it
      nodes.forEach { node ->
        styleNode.logger?.i { "Adding layer ${node.layer.id} below ${tail.layer.id}" }
        styleNode.style.addLayerBelow(tail.layer.id, node.layer)
        node.markAdded()
      }
    }
  }

  private fun LayerNode<*>.markAdded() {
    if (anchor is Anchor.Replace)
      replacementCounters[anchor] = replacementCounters.getValue(anchor) + 1
    added = true
  }

  private fun Anchor.validate() {
    layerIdOrNull?.let { layerId ->
      // hack: if the style unloaded before baseLayers was initialized, there's nothing to validate
      require(baseLayers.containsKey(layerId) || styleNode.style.isUnloaded) {
        "Layer ID '$layerId' not found in base style"
      }
    }
  }

  private val Anchor.layerIdOrNull: String?
    get() =
      when (this) {
        is Anchor.Above -> layerId
        is Anchor.Below -> layerId
        is Anchor.Replace -> layerId
        else -> null
      }
}
