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
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.LayerNode
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.ResolvedLayerDefinition
import org.maplibre.compose.style.StyleProperty
import org.maplibre.compose.style.styleFontScale
import org.maplibre.compose.util.MaplibreComposable

/**
 * Declares an engine-supported layer [type] using named properties. Use this to wrap layer types
 * that do not have a built-in composable. The renderer must support the type and its properties.
 *
 * [properties] describes the complete layer; omitted properties are removed on recomposition. Call
 * composable helpers before this regular Kotlin builder. Expressions support painters, bitmaps, and
 * text units. Paint transitions follow the system animation-duration scale.
 *
 * [source] supplies a managed source. Alternatively, a root `source` property can name a source
 * already in the style. If both are provided, their IDs must match.
 *
 * The surrounding [Anchor] determines placement. The layer is removed when it leaves composition
 * and restored after a style reload. Its handle is read-only. Support for feature queries depends
 * on the layer type.
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
