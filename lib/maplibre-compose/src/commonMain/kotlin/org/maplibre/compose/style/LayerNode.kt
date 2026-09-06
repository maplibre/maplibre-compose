package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.map.HoverEvent
import org.maplibre.compose.map.SubscriptionSlot
import org.maplibre.compose.util.FeaturesClickHandler

internal class LayerNode<T : Layer>(val layer: T, val anchor: Anchor) : MapNode {
  internal val clickSubscription = SubscriptionSlot()
  internal var onClick: FeaturesClickHandler? = null
    set(value) {
      clickSubscription.update(value != null)
      field = value
    }

  internal val contextClickSubscription = SubscriptionSlot()
  internal var onContextClick: FeaturesClickHandler? = null
    set(value) {
      contextClickSubscription.update(value != null)
      field = value
    }

  internal val doubleClickSubscription = SubscriptionSlot()
  internal var onDoubleClick: FeaturesClickHandler? = null
    set(value) {
      doubleClickSubscription.update(value != null)
      field = value
    }

  internal val twoFingerClickSubscription = SubscriptionSlot()
  internal var onTwoFingerClick: FeaturesClickHandler? = null
    set(value) {
      twoFingerClickSubscription.update(value != null)
      field = value
    }

  internal var hitPadding: Dp = 0.dp
  internal val hoverSubscription = SubscriptionSlot()
  internal var onHover: ((HoverEvent) -> Unit)? = null
    set(value) {
      hoverSubscription.update(value != null)
      field = value
    }

  override fun toString(): String {
    return "LayerNode(layer=${layer.id}, anchor=$anchor)"
  }
}
