package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.LayerProperty
import org.maplibre.compose.sources.Source

/** Receives immutable snapshots only when composition applies its node updates. */
internal class LayerNode(var definition: LayerDefinition, var anchor: Anchor) : MapNode {
  val registration = Any()
  var imageProperties: Map<StyleProperty, LayerProperty<*>> = emptyMap()
  var source: Source? = null
  var onClick: FeaturesClickHandler? = null
  var onLongClick: FeaturesClickHandler? = null
  var onDoubleClick: FeaturesClickHandler? = null
  var hitPadding: Dp = 0.dp

  // Every resolved image republishes all layers, so an unchanged layer reuses its snapshot.
  private var previous: Previous? = null
  private var propertyImages = emptyMap<StyleProperty, List<StyleImageDefinition>>()
  val images: List<StyleImageDefinition>
    get() = propertyImages.values.flatten()

  fun snapshot(
    resolved: Map<StyleImageRequest, StyleImageDefinition>,
    resolvedIds: Map<StyleImageRequest, String>,
  ): StyleSnapshot.Layer {
    val layerImages =
      imageProperties.values.flatMap { property -> property.images.map { resolved[it] } }
    previous
      ?.takeIf {
        it.definition === definition &&
          it.imageProperties === imageProperties &&
          it.snapshot.anchor == anchor &&
          it.snapshot.onClick == onClick &&
          it.snapshot.onLongClick == onLongClick &&
          it.snapshot.onDoubleClick == onDoubleClick &&
          it.snapshot.hitPadding == hitPadding &&
          sameInstances(it.images, layerImages)
      }
      ?.let {
        return it.snapshot
      }

    val previousDefinition = previous?.snapshot?.definition
    val resolvedDefinition =
      if (imageProperties.isEmpty()) {
        propertyImages = emptyMap()
        definition
      } else {
        val nextImages = mutableMapOf<StyleProperty, List<StyleImageDefinition>>()
        val value = definition.value.toMutableMap()
        imageProperties.forEach { (path, property) ->
          val ready = property.images.all { it in resolved }
          val result =
            if (ready) {
              nextImages[path] = property.images.map { resolved.getValue(it) }
              property.resolve(resolvedIds)
            } else {
              propertyImages[path]?.let { nextImages[path] = it }
              previousDefinition?.value?.let { old ->
                val container = if (path.section == null) old else old[path.section] as? JsonObject
                container?.get(path.name)
              }
            }
          if (result != null) {
            if (path.section == null) value[path.name] = result
            else {
              val section = (value[path.section] as? JsonObject).orEmpty().toMutableMap()
              if (result != JsonNull) section[path.name] = result
              value[path.section] = JsonObject(section)
            }
          }
        }
        propertyImages = nextImages
        definition.copy(value = JsonObject(value))
      }
    val snapshot =
      StyleSnapshot.Layer(
        resolvedDefinition,
        anchor,
        onClick,
        onLongClick,
        onDoubleClick,
        hitPadding,
        registration,
      )
    previous = Previous(definition, imageProperties, layerImages, snapshot)
    return snapshot
  }

  private fun sameInstances(a: List<StyleImageDefinition?>, b: List<StyleImageDefinition?>) =
    a.size == b.size && a.indices.all { a[it] === b[it] }

  private class Previous(
    val definition: LayerDefinition,
    val imageProperties: Map<StyleProperty, LayerProperty<*>>,
    val images: List<StyleImageDefinition?>,
    val snapshot: StyleSnapshot.Layer,
  )

  override fun toString(): String = "LayerNode(layer=${definition.id}, anchor=$anchor)"
}

internal data class StyleProperty(val section: String?, val name: String)
