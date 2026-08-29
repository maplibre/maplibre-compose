package org.maplibre.compose.style

import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer

internal class LayerManager(private val styleNode: StyleNode) {
  private val baseLayers = styleNode.style.getLayers().associateBy { it.id }

  private val userLayers = mutableListOf<LayerNode<*>>()
  private val handles = mutableMapOf<LayerNode<*>, LayerHandle>()

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
    if (!node.added) return
    if (!styleNode.style.isLoaded) {
      node.added = false
      return
    }

    // special handling for Replace anchors
    // restore the original before removing if this layer was the last replacement
    val anchor = node.anchor
    if (anchor is Anchor.Replace) {
      val count = replacementCounters.getValue(anchor) - 1
      if (count > 0) replacementCounters[anchor] = count
      else {
        replacementCounters.remove(anchor)
        styleNode.logger?.i { "Restoring layer ${anchor.layerId}" }
        install(replacedLayers.remove(anchor)!!, beforeLayerId = node.layer.id)
      }
    }

    styleNode.logger?.i { "Removing layer ${node.layer.id}" }
    handles.remove(node)?.remove()
    node.added = false
  }

  internal fun moveLayer(node: LayerNode<*>, oldIndex: Int, index: Int) {
    styleNode.logger?.i { "Moving layer ${node.layer.id} from $oldIndex to $index" }
    removeLayer(node, oldIndex)
    addLayer(node, index)
    styleNode.logger?.i { "Done moving layer ${node.layer.id}" }
  }

  internal fun applyChanges() {
    if (!styleNode.style.isLoaded) return

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
          install(missedLayer, beforeLayerId = layer.id)
          missedLayer.markAdded()
        }
      }

      if (!node.added) {
        // we found a layer to add; let's try to add it, or queue it up until we find a head
        tailLayerIds[anchor]?.let { tailLayerId ->
          styleNode.logger?.i { "Adding layer ${layer.id} above $tailLayerId" }
          install(node, beforeLayerId = layerIdAbove(tailLayerId))
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
        is Anchor.Top -> install(tail, beforeLayerId = "")
        is Anchor.Bottom ->
          install(tail, beforeLayerId = styleNode.style.layerIds().firstOrNull().orEmpty())
        is Anchor.Above -> install(tail, beforeLayerId = layerIdAbove(anchor.layerId))
        is Anchor.Below -> install(tail, beforeLayerId = anchor.layerId)
        is Anchor.Replace -> {
          val layerToReplace = styleNode.style.getLayer(anchor.layerId)!!
          install(tail, beforeLayerId = layerIdAbove(layerToReplace.id))
          styleNode.logger?.i { "Replacing layer ${layerToReplace.id} with ${tail.layer.id}" }
          styleNode.style.removeLayer(layerToReplace.id)
          replacedLayers[anchor] = layerToReplace
          replacementCounters[anchor] = 0
        }
      }
      tail.markAdded()

      // and add the rest below it
      nodes.forEach { node ->
        styleNode.logger?.i { "Adding layer ${node.layer.id} below ${tail.layer.id}" }
        install(node, beforeLayerId = tail.layer.id)
        node.markAdded()
      }
    }

    userLayers.forEach { node -> handles[node]?.update(node.layer.definition()) }
  }

  private fun install(node: LayerNode<*>, beforeLayerId: String) {
    handles[node] = LayerHandle(styleNode.style, node.layer.definition(), beforeLayerId)
  }

  private fun install(layer: Layer, beforeLayerId: String) {
    LayerHandle(styleNode.style, layer.definition(), beforeLayerId)
  }

  private fun layerIdAbove(layerId: String): String {
    val ids = styleNode.style.layerIds()
    val index = ids.indexOf(layerId)
    require(index >= 0) { "Layer ID '$layerId' not found in base style" }
    return ids.getOrNull(index + 1).orEmpty()
  }

  private fun LayerNode<*>.markAdded() {
    if (anchor is Anchor.Replace)
      replacementCounters[anchor] = replacementCounters.getValue(anchor) + 1
    added = true
  }

  private fun Anchor.validate() {
    layerIdOrNull?.let { layerId ->
      // Every style switch briefly inserts content composed against the incoming style into the
      // outgoing style's node, so its anchors name layers this node never had; the unloaded flag
      // marks that window. This throws from inside `addLayer` before `userLayers` is updated, so a
      // real failure here also desynchronizes the manager's list from Compose's child list.
      require(baseLayers.containsKey(layerId) || !styleNode.style.isLoaded) {
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
