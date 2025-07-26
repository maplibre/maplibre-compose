package org.maplibre.compose.style

import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.util.FeaturesClickHandler

internal class LayerNode<T : Layer>(val layer: T, val anchor: Anchor) : MapNode() {
  internal var added: Boolean = false

  override fun allowsChild(node: MapNode) = false

  internal var onClick: FeaturesClickHandler? = null
  internal var onLongClick: FeaturesClickHandler? = null

  override fun toString(): String {
    return "LayerNode(layer=${layer.id}, anchor=$anchor, added=$added)"
  }
}
