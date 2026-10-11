package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.style.internal.StyleOverrideDefinition

/** An immutable, engine-ready snapshot of committed style content. */
internal data class StyleSnapshot(
  val sources: List<SourceDefinition>,
  val layers: List<Layer>,
  val images: List<StyleImageDefinition>,
  /** The animator duration scale the composition read; layer transitions are scaled by it. */
  val animatorDurationScale: Float = 1f,
  val fontScale: Float? = null,
  /** At least one committed property is waiting for painter preparation. */
  val imagesPending: Boolean = false,
  val overrides: StyleOverrideDefinition = StyleOverrideDefinition(),
) {
  init {
    requireUniqueIds("Source", sources.map(SourceDefinition::id))
    requireUniqueIds("Layer", layers.map { it.definition.id })
    requireUniqueIds("Image", images.map(StyleImageDefinition::id))
  }

  private fun requireUniqueIds(kind: String, ids: List<String>) {
    val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    require(duplicate == null) { "$kind ID '$duplicate' is declared more than once" }
  }

  companion object {
    val Empty = StyleSnapshot(emptyList(), emptyList(), emptyList())
  }

  /** One layer definition at its explicit position in a style snapshot. */
  data class Layer(
    val definition: LayerDefinition,
    val anchor: Anchor,
    val onClick: FeaturesClickHandler?,
    val onLongClick: FeaturesClickHandler?,
    val onDoubleClick: FeaturesClickHandler? = null,
    val hitPadding: Dp = 0.dp,
    val registration: Any? = null,
  )
}
