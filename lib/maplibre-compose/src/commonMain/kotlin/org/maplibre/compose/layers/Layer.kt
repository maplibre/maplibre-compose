package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.LayerNode
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.ResolvedLayerDefinition
import org.maplibre.compose.style.StyleProperty
import org.maplibre.compose.style.styleFontScale
import org.maplibre.compose.util.MaplibreComposable

/**
 * Declares a layer of any engine-supported [type]. The surrounding [Anchor] determines placement.
 * The layer is removed when it leaves composition and restored after a style reload.
 *
 * [properties] describes the complete layer on each invocation; omitted properties are removed. It
 * is a regular Kotlin builder, so call composable helpers before this block. Named properties and
 * JSON pass to the engine without Compose's style-spec filtering. Expressions support managed
 * images and text units. Paint transitions follow the system animation-duration scale.
 *
 * [source] installs and retains a managed source. Without it, a root `source` property may name an
 * existing source. Conflicting source IDs are an error. Paint, layout, filter, and zoom changes
 * update the installed layer; changes to its type, source, source-layer, or other root fields
 * replace it. Engine support for properties and feature queries depends on the layer type.
 * Composition owns the layer; its handle is read-only.
 */
@Composable
@MaplibreComposable
public fun Layer(
  id: String,
  type: String,
  source: Source? = null,
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
  onDoubleClick: FeaturesClickHandler? = null,
  hitPadding: Dp = 0.dp,
  properties: LayerProperties.() -> Unit = {},
) {
  Layer(id, type, source, onClick, onLongClick, onDoubleClick, hitPadding, false, properties)
}

// Built-ins retain their documented cross-engine filtering; preparation and ownership are shared.
@Composable
@MaplibreComposable
internal fun Layer(
  id: String,
  type: String,
  source: Source? = null,
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
  onDoubleClick: FeaturesClickHandler? = null,
  hitPadding: Dp = 0.dp,
  filterUnsupportedProperties: Boolean,
  properties: LayerProperties.() -> Unit,
) {
  validateLayer(id, type, hitPadding)
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  val fontScale = styleFontScale()
  val locals = currentComposer.currentCompositionLocalMap
  val cache =
    remember(density, direction, fontScale, locals) {
      // Headless style compositions need a graphics context only if a property contains a painter.
      LayerPropertyCache(
        LayerPropertyCompiler(density, direction, fontScale) { locals[LocalGraphicsContext] }
      )
    }
  cache.begin()
  val builder = LayerProperties(cache)
  val snapshot =
    try {
      builder.properties()
      builder.finish(id, type, source?.id, filterUnsupportedProperties)
    } finally {
      builder.close()
      cache.end()
    }
  LayerNode(
    snapshot.definition,
    snapshot.images,
    source,
    onClick,
    onLongClick,
    onDoubleClick,
    hitPadding,
  )
}

/**
 * Declares a complete JSON layer without Compose's style-spec filtering or expression conversion.
 * [id] supplies the identity; an `id` in [definition] must match. Raw image names must already
 * exist in the style. Use [Layer] for expressions containing painters or bitmaps.
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
  validateLayer(id, type.content, hitPadding)
  require(definition["id"] == null || definition["id"] == JsonPrimitive(id)) {
    "Layer ID conflicts with its definition"
  }
  listOf("layout", "paint").forEach { section ->
    require(definition[section] == null || definition[section] is JsonObject) {
      "Layer $section must be an object"
    }
  }
  val sourceId = layerSourceId(definition, source?.id)
  val json = definition.mapValuesTo(mutableMapOf()) { (_, value) -> value.snapshot() }
  json["id"] = JsonPrimitive(id)
  if (sourceId != null) json["source"] = JsonPrimitive(sourceId)
  val resolved =
    ResolvedLayerDefinition(id, type.content, sourceId, JsonObject(json), scaleTransitions = false)
  LayerNode(resolved, emptyMap(), source, onClick, onLongClick, onDoubleClick, hitPadding)
}

@Composable
@MaplibreComposable
private fun LayerNode(
  definition: ResolvedLayerDefinition,
  images: Map<StyleProperty, LayerProperty<*>>,
  source: Source?,
  onClick: FeaturesClickHandler?,
  onLongClick: FeaturesClickHandler?,
  onDoubleClick: FeaturesClickHandler?,
  hitPadding: Dp,
) {
  val anchor = LocalAnchor.current
  val clickGroup = LocalLayerClickGroup.current
  key(definition.id, definition.type, definition.sourceId, definition.value["source-layer"]) {
    ComposeNode<LayerNode, MapNodeApplier>(
      factory = { LayerNode(definition, anchor) },
      update = {
        set(definition) { this.definition = it }
        set(images) { imageProperties = it }
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

private fun validateLayer(id: String, type: String, hitPadding: Dp) {
  require(id.isNotBlank()) { "Layer ID must not be blank" }
  require(type.isNotBlank()) { "Layer type must not be blank" }
  require(hitPadding.value.isFinite() && hitPadding.value >= 0f) {
    "hitPadding must be finite and nonnegative"
  }
}

internal fun layerSourceId(
  properties: Map<String, JsonElement>,
  managedSourceId: String?,
): String? {
  val declared =
    properties["source"]?.let {
      require(it is JsonPrimitive && it.isString) { "Layer source must be a string" }
      it.content
    }
  require(managedSourceId == null || declared == null || managedSourceId == declared) {
    "Layer source conflicts with its managed source"
  }
  return managedSourceId ?: declared
}
