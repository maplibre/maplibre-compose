package org.maplibre.compose.style

import androidx.compose.runtime.AbstractApplier

internal class MapNodeApplier(private val styleRoot: StyleNode) :
  AbstractApplier<MapNode>(styleRoot) {
  private var hasStructuralChanges = false

  override fun insertBottomUp(index: Int, instance: MapNode) {}

  override fun insertTopDown(index: Int, instance: MapNode) {
    hasStructuralChanges = true
    current.allowsChild(instance)
    current.children.add(index, instance)
    current.onChildInserted(index, instance)
  }

  override fun move(from: Int, to: Int, count: Int) {
    hasStructuralChanges = true
    val moved = current.children.slice(from until (from + count))
    current.children.move(from, to, count)
    (if (from < to) (0 until count) else (count - 1 downTo 0)).forEach { i ->
      current.onChildMoved(from, to, moved[i])
    }
  }

  override fun onClear() = remove(0, current.children.size)

  override fun remove(index: Int, count: Int) {
    hasStructuralChanges = true
    val removed = current.children.slice(index until (index + count))
    current.children.remove(index, count)
    removed.forEach { instance -> current.onChildRemoved(index, instance) }
  }

  override fun onEndChanges() {
    if (!hasStructuralChanges) return
    hasStructuralChanges = false
    styleRoot.scheduleApplyChanges()
  }
}
