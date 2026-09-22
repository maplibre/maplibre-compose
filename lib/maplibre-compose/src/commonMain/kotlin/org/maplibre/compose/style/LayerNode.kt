package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.LayerProperty
import org.maplibre.compose.sources.Source

/** Receives immutable snapshots only when composition applies its node updates. */
internal class LayerNode(var definition: ResolvedLayerDefinition, var anchor: Anchor) : MapNode {
  val registration = Any()
  var imageProperties: Map<StyleProperty, LayerProperty<*>> = emptyMap()
  var source: Source? = null
  var onClick: FeaturesClickHandler? = null
  var onLongClick: FeaturesClickHandler? = null
  var onDoubleClick: FeaturesClickHandler? = null
  var clickGroup: Any? = null
  var hitPadding: Dp = 0.dp

  override fun toString(): String = "LayerNode(layer=${definition.id}, anchor=$anchor)"
}
