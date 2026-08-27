package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.toCompiledExpression

/**
 * An imperative handle over one live layer, from [MapState.layers].
 *
 * A write reaches the live style directly, without a declaration in the style content.
 * [MapState.baseStyle] reloads drop imperative writes: the reloaded style starts from its own
 * definition, so reapply the writes after the load.
 *
 * Writes are allowed only on a map-owned layer: a layer from the base style. A layer that the style
 * content composed is composition-owned, and every write on its handle throws
 * [IllegalStateException]; change that layer by recomposing the content.
 *
 * The handle captures the layer's definition when [MapState.layers] returns it. Property getters
 * answer from that definition plus this handle's own writes; get a fresh handle to observe a change
 * that another handle or another owner made.
 */
public class LayerHandle
internal constructor(private val state: MapState, private val descriptor: Layer) {

  /** The layer's id in the style. */
  public val id: String
    get() = descriptor.id

  /** The layer's `type` in the style spec, such as `fill`. */
  public val type: String
    get() = descriptor.type

  /** Whether the layer draws, from the `visibility` layout property. */
  public var visible: Boolean
    get() = descriptor.visible
    set(value) {
      state.checkLayerWritable(id)
      descriptor.visible = value
    }

  /** The minimum zoom level at which the layer draws. */
  public var minZoom: Float
    get() = descriptor.minZoom
    set(value) {
      state.checkLayerWritable(id)
      descriptor.minZoom = value
    }

  /** The maximum zoom level at which the layer draws. */
  public var maxZoom: Float
    get() = descriptor.maxZoom
    set(value) {
      state.checkLayerWritable(id)
      descriptor.maxZoom = value
    }

  /**
   * The layer's filter: the condition that source features must match to be drawn. Assigning
   * [nil][org.maplibre.compose.expressions.dsl.nil] clears the filter, and every feature matches.
   *
   * The getter answers with a compiled form of the filter that this handle's definition captured or
   * that this handle wrote; it reads as `nil` while the layer has no filter.
   */
  public var filter: Expression<BooleanValue>
    get() =
      descriptor
        .readProperty("filter")
        .takeIf { it !is JsonNull }
        ?.toCompiledExpression()
        ?.cast<BooleanValue>() ?: nil()
    set(value) {
      state.checkLayerWritable(id)
      descriptor.setFilterJson(state.compileLayerProperty(value))
    }

  /**
   * Sets the layout property named [name] in the style spec to [value]. A value that MapLibre
   * rejects is logged, and the layer keeps its previous value.
   */
  public fun setLayoutProperty(name: String, value: Expression<*>) {
    state.checkLayerWritable(id)
    descriptor.setLayoutProperty(name, state.compileLayerProperty(value))
  }

  /**
   * Sets the paint property named [name] in the style spec to [value]. A value that MapLibre
   * rejects is logged, and the layer keeps its previous value.
   */
  public fun setPaintProperty(name: String, value: Expression<*>) {
    state.checkLayerWritable(id)
    descriptor.setPaintProperty(name, state.compileLayerProperty(value))
  }

  /**
   * Returns the style-spec JSON of the property named [name], from the live layer where the engine
   * reports it and from this handle's definition otherwise. Returns null for a property the layer
   * holds no value for.
   */
  public fun property(name: String): JsonElement? =
    descriptor.readProperty(name).takeIf { it !is JsonNull }
}
