package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.Layer

internal class LayerNode<T : Layer>(val layer: T, val anchor: Anchor) : MapNode {
  internal var onClick: FeaturesClickHandler? = null

  internal var onLongClick: FeaturesClickHandler? = null

  internal var onDoubleClick: FeaturesClickHandler? = null

  internal var hitPadding: Dp = 0.dp

  override fun toString(): String {
    return "LayerNode(layer=${layer.id}, anchor=$anchor)"
  }
}
