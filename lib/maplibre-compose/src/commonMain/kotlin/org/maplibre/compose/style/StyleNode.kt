package org.maplibre.compose.style

import co.touchlab.kermit.Logger

internal class StyleNode(var style: SafeStyle, internal var logger: Logger?) : MapNode() {

  internal val sourceManager = SourceManager(this)
  internal val layerManager = LayerManager(this)
  internal val imageManager = ImageManager(this)

  override fun allowsChild(node: MapNode) = node is LayerNode<*>

  override fun onChildRemoved(oldIndex: Int, node: MapNode) {
    node as LayerNode<*>
    layerManager.removeLayer(node, oldIndex)
  }

  override fun onChildInserted(index: Int, node: MapNode) {
    node as LayerNode<*>
    layerManager.addLayer(node, index)
  }

  override fun onChildMoved(oldIndex: Int, index: Int, node: MapNode) {
    node as LayerNode<*>
    layerManager.moveLayer(node, oldIndex, index)
  }

  override fun onEndChanges() {
    // Only layers. Sources reload themselves when the set changes, which is not here: a source is
    // referenced and released from a DisposableEffect, and Compose dispatches those after the
    // applier's end-of-changes hook. Reloading here polled for a change that had not happened yet
    // and would be picked up by the next commit anyway — so on desktop, where reading the source
    // list means a blocking hop to the map's owner thread, an animating layer paid for a full
    // source enumeration on every frame it recomposed.
    layerManager.applyChanges()
  }
}
