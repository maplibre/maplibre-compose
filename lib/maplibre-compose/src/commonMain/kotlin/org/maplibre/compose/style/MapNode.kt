package org.maplibre.compose.style

internal sealed class MapNode {
  val children = mutableListOf<MapNode>()

  abstract fun allowsChild(node: MapNode): Boolean

  open fun onChildInserted(index: Int, node: MapNode) {}

  open fun onChildRemoved(oldIndex: Int, node: MapNode) {}

  open fun onChildMoved(oldIndex: Int, index: Int, node: MapNode) {}

  open fun onEndChanges() {}
}
