package org.maplibre.compose.style

import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.util.FeaturesClickHandler

/** One complete immutable evaluation of a [StyleComposition]. */
internal class DesiredStyleRevision(
  sources: List<SourceDefinition>,
  layers: List<DesiredStyleLayer>,
  images: List<StyleImageDefinition>,
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
      images == other.images

  override fun hashCode(): Int =
    31 * (31 * sources.hashCode() + layers.hashCode()) + images.hashCode()

  override fun toString(): String =
    "DesiredStyleRevision(sources=$sources, layers=$layers, images=$images)"

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
)
