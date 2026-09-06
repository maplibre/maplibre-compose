package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.interaction.internal.SubscriptionSlot
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.Layer

internal class LayerNode<T : Layer>(val layer: T, val anchor: Anchor) : MapNode {
  internal val clickSubscription = SubscriptionSlot()
  internal var onClick: FeaturesClickHandler? = null
    set(value) {
      clickSubscription.update(value != null)
      field = value
    }

  internal val longClickSubscription = SubscriptionSlot()
  internal var onLongClick: FeaturesClickHandler? = null
    set(value) {
      longClickSubscription.update(value != null)
      field = value
    }

  internal val doubleClickSubscription = SubscriptionSlot()
  internal var onDoubleClick: FeaturesClickHandler? = null
    set(value) {
      doubleClickSubscription.update(value != null)
      field = value
    }

  internal var hitPadding: Dp = 0.dp

  override fun toString(): String {
    return "LayerNode(layer=${layer.id}, anchor=$anchor)"
  }
}
