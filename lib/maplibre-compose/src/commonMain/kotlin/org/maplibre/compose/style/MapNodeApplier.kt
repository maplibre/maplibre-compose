package org.maplibre.compose.style

import androidx.compose.runtime.AbstractApplier

internal class MapNodeApplier(private val styleRoot: StyleNode) :
  AbstractApplier<MapNode>(styleRoot) {
  override fun insertBottomUp(index: Int, instance: MapNode) {}

  override fun insertTopDown(index: Int, instance: MapNode) {
    check(current === styleRoot) { "Style declarations cannot contain child nodes" }
    styleRoot.children.add(index, instance)
  }

  override fun move(from: Int, to: Int, count: Int) {
    styleRoot.children.move(from, to, count)
  }

  override fun onClear() = remove(0, styleRoot.children.size)

  override fun remove(index: Int, count: Int) {
    styleRoot.children.remove(index, count)
  }

  // Resource preparation starts from this committed description, including child-only updates.
  override fun onEndChanges() = styleRoot.commit()
}
