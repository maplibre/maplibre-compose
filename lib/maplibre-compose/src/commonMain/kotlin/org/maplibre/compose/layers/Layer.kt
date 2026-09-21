package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.LayerNode
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.PreparedLayerDefinition
import org.maplibre.compose.style.StyleProperty
import org.maplibre.compose.util.MaplibreComposable

/**
 * Declares a layer using named JSON properties or expressions. The surrounding [Anchor] determines
 * placement. The layer is removed when it leaves composition and restored after a style reload.
 *
 * [source] installs and retains a managed source. Without it, the definition may name an existing
 * source using its root `source` property. A conflicting source ID is an error.
 *
 * Paint, layout, filter, and zoom changes update the installed layer. Changes to its type, source,
 * source-layer, or other root fields replace it. Engine support for properties and feature queries
 * depends on the layer type. Composition owns its definition; its handle is read-only.
 */
@Composable
@MaplibreComposable
public fun Layer(
  id: String,
  definition: LayerDefinition,
  source: Source? = null,
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
  onDoubleClick: FeaturesClickHandler? = null,
  hitPadding: Dp = 0.dp,
) {
  require(id.isNotBlank()) { "Layer ID must not be blank" }
  require(hitPadding.value.isFinite() && hitPadding.value >= 0f) {
    "hitPadding must be finite and nonnegative"
  }
  val compiler = rememberPropertyCompiler()
  val properties = linkedMapOf<StyleProperty, JsonElement>()
  val images = linkedMapOf<StyleProperty, LayerProperty<*>>()
  definition.properties.forEach { (path, value) ->
    when (value) {
      is LayerValue.Json -> properties[path] = value.value
      is LayerValue.Expression ->
        key(path) {
          val property = compiler.withUnits(value.units)(value.value)
          if (property.images.isNotEmpty()) images[path] = property
          else
            property
              .resolve(emptyMap())
              .takeUnless { it == JsonNull }
              ?.let { properties[path] = it }
        }
    }
  }
  val declaredSource =
    (properties[SourceProperty] ?: definition.json?.get("source"))?.let {
      require(it is JsonPrimitive && it.isString) { "Layer source must be a string" }
      it.content
    }
  require(source == null || declaredSource == null || source.id == declaredSource) {
    "Layer source conflicts with its managed source"
  }
  val prepared =
    PreparedLayerDefinition(
      id = id,
      type = definition.type,
      sourceId = source?.id ?: declaredSource,
      properties = properties,
      imageProperties = images,
      json = definition.json,
      unsupportedProperties = definition.unsupportedProperties,
      filterUnsupportedProperties = definition.filterUnsupportedProperties,
    )
  val anchor = LocalAnchor.current
  val clickGroup = LocalLayerClickGroup.current
  key(
    id,
    definition.type,
    prepared.sourceId,
    properties[SourceLayerProperty] ?: definition.json?.get("source-layer"),
  ) {
    ComposeNode<LayerNode, MapNodeApplier>(
      factory = { LayerNode(prepared, anchor) },
      update = {
        set(prepared) { updateDefinition(it) }
        set(source) { this.source = it }
        set(anchor) { this.anchor = it }
        set(onClick) { this.onClick = it }
        set(onLongClick) { this.onLongClick = it }
        set(onDoubleClick) { this.onDoubleClick = it }
        set(hitPadding) { this.hitPadding = it }
        set(clickGroup) { this.clickGroup = it }
      },
    )
  }
}

/**
 * Declares a complete JSON layer without Compose's style-spec filtering or expression conversion.
 * [id] supplies the identity; an `id` in [definition] must match. Raw image names must already
 * exist in the style. Use [Layer] and [layerDefinition] for expressions containing painters or
 * bitmaps.
 *
 * Raw transition timing is passed unchanged, without the system animation-duration scale. Other
 * lifecycle, source ownership, and update rules are the same as [Layer].
 */
@Composable
@MaplibreComposable
public fun RawLayer(
  id: String,
  definition: JsonObject,
  source: Source? = null,
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
  onDoubleClick: FeaturesClickHandler? = null,
  hitPadding: Dp = 0.dp,
) {
  val type = definition["type"]
  require(type is JsonPrimitive && type.isString && type.content.isNotBlank()) {
    "Layer type must be a nonblank string"
  }
  require(definition["id"] == null || definition["id"] == JsonPrimitive(id)) {
    "Layer ID conflicts with its definition"
  }
  listOf("layout", "paint").forEach { section ->
    require(definition[section] == null || definition[section] is JsonObject) {
      "Layer $section must be an object"
    }
  }
  val declaration =
    LayerDefinition(type.content, emptyMap(), json = definition.snapshot() as JsonObject)
  Layer(id, declaration, source, onClick, onLongClick, onDoubleClick, hitPadding)
}

private val SourceProperty = StyleProperty(null, "source")
private val SourceLayerProperty = StyleProperty(null, "source-layer")
