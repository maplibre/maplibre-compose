package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.map.HoverEvent
import org.maplibre.compose.util.FeaturesClickHandler

internal class LayerNode<T : Layer>(val layer: T, val anchor: Anchor) : MapNode() {
  override fun allowsChild(node: MapNode) = false

  internal var onClick: FeaturesClickHandler? = null
  internal var onLongClick: FeaturesClickHandler? = null
  internal var onDoubleClick: FeaturesClickHandler? = null
  internal var onTwoFingerClick: FeaturesClickHandler? = null
  internal var hitPadding: Dp = 0.dp
  internal var onHover: ((HoverEvent) -> Unit)? = null

  override fun toString(): String {
    return "LayerNode(layer=${layer.id}, anchor=$anchor)"
  }
}
