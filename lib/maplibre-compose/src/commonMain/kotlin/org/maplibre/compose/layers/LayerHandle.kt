package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.LayerPropertyKind

/**
 * An imperative handle over one live layer, from [MapState.layers].
 *
 * A write reaches the live style directly, without a declaration in the style composition.
 * [MapState.baseStyle] reloads drop imperative writes: the reloaded style starts from its own
 * definition, so reapply the writes after the load. The handle names the style generation it was
 * taken from: after a reload, a write through it throws [IllegalStateException], so reapply through
 * a fresh handle from [MapState.layers].
 *
 * Writes are allowed only on a map-owned layer: a layer from the base style. A layer that the style
 * content composed is composition-owned, and every write on its handle throws
 * [IllegalStateException]; change that layer by recomposing the content.
 *
 * Getters read the live layer. Get a fresh handle after a reload, because a write through this one
 * is unauthorized once the generation changes.
 */
public class LayerHandle
internal constructor(
  private val state: MapState,
  private val styleGeneration: Long,
  private val bindingGeneration: Long,
  public val id: String,
) {

  private fun liveLayer(): Layer? = state.liveLayer(styleGeneration, bindingGeneration, id)

  private fun writeProperty(kind: LayerPropertyKind, name: String, value: JsonElement) {
    state.writeLayerProperty(styleGeneration, bindingGeneration, id, name, value, kind)
  }

  /** The layer's `type` in the style spec, such as `fill`. */
  public val type: String
    get() = liveLayer()?.type ?: ""

  /** Whether the layer draws, from the `visibility` layout property. */
  public var visible: Boolean
    get() = (liveProperty("visibility") as? JsonPrimitive)?.content != "none"
    set(value) {
      writeProperty(
        LayerPropertyKind.LAYOUT,
        "visibility",
        JsonPrimitive(if (value) "visible" else "none"),
      )
    }

  /** The minimum zoom level at which the layer draws. */
  public var minZoom: Float
    get() = (liveProperty("minzoom") as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
    set(value) {
      writeProperty(LayerPropertyKind.ROOT, "minzoom", JsonPrimitive(value))
    }

  /** The maximum zoom level at which the layer draws. */
  public var maxZoom: Float
    get() = (liveProperty("maxzoom") as? JsonPrimitive)?.content?.toFloatOrNull() ?: 24f
    set(value) {
      writeProperty(LayerPropertyKind.ROOT, "maxzoom", JsonPrimitive(value))
    }

  private fun liveProperty(name: String): JsonElement? =
    state.liveLayerProperty(styleGeneration, bindingGeneration, id, name)

  /**
   * Sets the layer's filter: the condition that source features must match to be drawn. Passing
   * [nil][org.maplibre.compose.expressions.dsl.nil] clears the filter, and every feature matches.
   */
  public fun setFilter(filter: Expression<BooleanValue>) {
    state.writeLayerFilter(
      styleGeneration,
      bindingGeneration,
      id,
      state.compileLayerProperty(filter),
    )
  }

  /**
   * Sets the layout property named [name] in the style spec to [value]. A value that MapLibre
   * rejects is logged, and the layer keeps its previous value.
   */
  public fun setLayoutProperty(name: String, value: Expression<*>) {
    writeProperty(LayerPropertyKind.LAYOUT, name, state.compileLayerProperty(value))
  }

  /**
   * Sets the paint property named [name] in the style spec to [value]. A value that MapLibre
   * rejects is logged, and the layer keeps its previous value.
   */
  public fun setPaintProperty(name: String, value: Expression<*>) {
    writeProperty(LayerPropertyKind.PAINT, name, state.compileLayerProperty(value))
  }

  /**
   * Returns the style-spec JSON of the property named [name] from the live layer. Returns null for
   * a property the layer holds no value for, or when this handle's generation is no longer current.
   */
  public fun property(name: String): JsonElement? =
    liveLayer()?.readProperty(name).takeIf { it !is JsonNull }
}
