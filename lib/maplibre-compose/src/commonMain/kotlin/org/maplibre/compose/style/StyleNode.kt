package org.maplibre.compose.style

import co.touchlab.kermit.Logger

internal class StyleNode(var style: SafeStyle, logger: Logger?) : MapNode() {
  internal var logger: Logger? = logger
    set(value) {
      field = value
      style.logger = value
    }

  init {
    style.logger = logger
  }

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
    // Only layers: sources are referenced and released from a DisposableEffect, which Compose
    // dispatches after this hook, so applying them here would always be a frame early.
    layerManager.applyChanges()
  }
}
