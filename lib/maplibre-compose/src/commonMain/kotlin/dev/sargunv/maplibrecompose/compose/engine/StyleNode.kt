package dev.sargunv.maplibrecompose.compose.engine

import co.touchlab.kermit.Logger
import dev.sargunv.maplibrecompose.core.SafeStyle

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
    sourceManager.applyChanges()
    layerManager.applyChanges()
  }
}
