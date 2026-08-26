package org.maplibre.compose.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger

internal class StyleNode(val binding: StyleBinding, internal var logger: Logger?) : MapNode() {

  internal val sourceManager = SourceManager(this)
  internal val layerManager = LayerManager(this)
  internal val imageManager = ImageManager(this)

  // A nested content scope can recompose without its StyleContent parent. This state invalidates
  // that parent after a structural change so it records the post-observer layer-application effect.
  private var applyGeneration by mutableIntStateOf(0)

  internal val currentApplyGeneration: Int
    get() = applyGeneration

  internal fun scheduleApplyChanges() {
    applyGeneration++
  }

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

  internal fun applyChanges() = layerManager.applyChanges()
}
