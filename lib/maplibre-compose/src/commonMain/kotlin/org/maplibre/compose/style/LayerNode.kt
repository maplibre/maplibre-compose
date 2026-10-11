package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.LayerProperty
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.internal.StyleValue

/** Receives immutable snapshots only when composition applies its node updates. */
internal class LayerNode(var definition: LayerDefinition, var anchor: Anchor) : MapNode {
  val registration = Any()
  var imageProperties: Map<StyleProperty, LayerProperty<*>> = emptyMap()
  var source: Source? = null
  var onClick: FeaturesClickHandler? = null
  var onLongClick: FeaturesClickHandler? = null
  var onDoubleClick: FeaturesClickHandler? = null
  var hitPadding: Dp = 0.dp

  private var previousDefinition: LayerDefinition? = null
  private var propertyImages = emptyMap<StyleProperty, List<StyleImageDefinition>>()
  val images: List<StyleImageDefinition>
    get() = propertyImages.values.flatten()

  fun snapshot(
    resolved: Map<StyleImageRequest, StyleImageDefinition>,
    resolvedIds: Map<StyleImageRequest, String>,
  ): StyleSnapshot.Layer {
    val resolvedDefinition =
      if (imageProperties.isEmpty()) {
        propertyImages = emptyMap()
        definition
      } else {
        val nextImages = mutableMapOf<StyleProperty, List<StyleImageDefinition>>()
        val value = definition.properties.toMutableMap()
        imageProperties.forEach { (path, property) ->
          val ready = property.images.all { it in resolved }
          val result =
            if (ready) {
              nextImages[path] = property.images.map { resolved.getValue(it) }
              property.resolve(resolvedIds)
            } else {
              propertyImages[path]?.let { nextImages[path] = it }
              previousDefinition?.properties?.let { old ->
                val container = if (path.section == null) old else old[path.section]?.objectValues
                container?.get(path.name)
              }
            }
          if (result != null) {
            if (path.section == null) value[path.name] = result
            else {
              val section = value[path.section]?.objectValues.orEmpty().toMutableMap()
              if (!result.isNull) section[path.name] = result
              value[path.section] = StyleValue.Object(section)
            }
          }
        }
        propertyImages = nextImages
        definition.copy(properties = value)
      }
    previousDefinition = resolvedDefinition
    return StyleSnapshot.Layer(
      resolvedDefinition,
      anchor,
      onClick,
      onLongClick,
      onDoubleClick,
      hitPadding,
      registration,
    )
  }

  override fun toString(): String = "LayerNode(layer=${definition.id}, anchor=$anchor)"
}

internal data class StyleProperty(val section: String?, val name: String)
