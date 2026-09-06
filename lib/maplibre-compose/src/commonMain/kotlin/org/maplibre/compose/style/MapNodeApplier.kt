package org.maplibre.compose.style

import androidx.compose.runtime.AbstractApplier

internal class MapNodeApplier(private val styleRoot: StyleNode) :
  AbstractApplier<MapNode>(styleRoot) {
  private var hasStructuralChanges = false

  override fun insertBottomUp(index: Int, instance: MapNode) {}

  override fun insertTopDown(index: Int, instance: MapNode) {
    hasStructuralChanges = true
    check(current === styleRoot) { "Layers cannot contain child nodes" }
    styleRoot.insertLayer(index, instance as LayerNode<*>)
  }

  override fun move(from: Int, to: Int, count: Int) {
    hasStructuralChanges = true
    styleRoot.children.move(from, to, count)
  }

  override fun onClear() = remove(0, styleRoot.children.size)

  override fun remove(index: Int, count: Int) {
    hasStructuralChanges = true
    styleRoot.children.remove(index, count)
  }

  override fun onEndChanges() {
    if (!hasStructuralChanges) return
    hasStructuralChanges = false
    styleRoot.scheduleApplyChanges()
  }
}
