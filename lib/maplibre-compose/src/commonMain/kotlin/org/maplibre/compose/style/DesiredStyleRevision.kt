package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.map.HoverEvent
import org.maplibre.compose.util.FeaturesClickHandler

/** One complete immutable evaluation of a map's style content. */
internal class DesiredStyleRevision(
  sources: List<SourceDefinition>,
  layers: List<DesiredStyleLayer>,
  images: List<StyleImageDefinition>,
  /** The animator duration scale the composition read; layer transitions are scaled by it. */
  val animatorDurationScale: Float = 1f,
) {
  val sources: List<SourceDefinition> = sources.toList()
  val layers: List<DesiredStyleLayer> = layers.toList()
  val images: List<StyleImageDefinition> = images.toList()

  init {
    requireUniqueIds("Source", sources.map(SourceDefinition::id))
    requireUniqueIds("Layer", layers.map { it.definition.id })
    requireUniqueIds("Image", images.map(StyleImageDefinition::id))
  }

  private fun requireUniqueIds(kind: String, ids: List<String>) {
    val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    require(duplicate == null) { "$kind ID '$duplicate' is declared more than once" }
  }

  override fun equals(other: Any?): Boolean =
    other is DesiredStyleRevision &&
      sources == other.sources &&
      layers == other.layers &&
      images == other.images &&
      animatorDurationScale == other.animatorDurationScale

  override fun hashCode(): Int =
    31 * (31 * (31 * sources.hashCode() + layers.hashCode()) + images.hashCode()) +
      animatorDurationScale.hashCode()

  override fun toString(): String =
    "DesiredStyleRevision(sources=$sources, layers=$layers, images=$images, " +
      "animatorDurationScale=$animatorDurationScale)"

  companion object {
    val Empty = DesiredStyleRevision(emptyList(), emptyList(), emptyList())
  }
}

/** One layer definition at its explicit position in a desired revision. */
internal data class DesiredStyleLayer(
  val definition: LayerDefinition,
  val anchor: Anchor,
  val onClick: FeaturesClickHandler?,
  val onLongClick: FeaturesClickHandler?,
  val onDoubleClick: FeaturesClickHandler? = null,
  val onTwoFingerClick: FeaturesClickHandler? = null,
  val hitPadding: Dp = 0.dp,
  val registration: Any? = null,
  val onHover: ((HoverEvent) -> Unit)? = null,
)
