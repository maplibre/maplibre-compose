package org.maplibre.compose.style

import androidx.compose.runtime.AbstractApplier

internal class MapNodeApplier(styleRoot: StyleNode) : AbstractApplier<MapNode>(styleRoot) {

  override fun insertBottomUp(index: Int, instance: MapNode) {}

  override fun insertTopDown(index: Int, instance: MapNode) {
    require(current.allowsChild(instance)) {
      "${current::class.simpleName} does not accept a ${instance::class.simpleName} child"
    }
    current.children.add(index, instance)
    current.onChildInserted(index, instance)
  }

  override fun move(from: Int, to: Int, count: Int) {
    current.children.move(from, to, count)
  }

  override fun onClear() = remove(0, current.children.size)

  override fun remove(index: Int, count: Int) {
    current.children.remove(index, count)
  }
}
