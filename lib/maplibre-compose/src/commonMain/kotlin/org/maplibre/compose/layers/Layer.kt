package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.style.LayerDefinition
import org.maplibre.compose.style.TRANSITION_SUFFIX
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.toTransitionJson
import org.maplibre.compose.util.toStyleJson

/** Style JSON keys that live at the top level of a layer rather than in layout or paint. */
private val ROOT_KEYS =
  setOf("id", "type", "source", "source-layer", "minzoom", "maxzoom", "filter")

internal sealed class Layer(val id: String) {

  /** The layer's `type` in the style spec, e.g. `fill`. */
  protected abstract val type: String

  /** The source this layer draws from, or null for layers that have none, such as background. */
  protected open val sourceId: String? = null

  private val layout = mutableMapOf<String, JsonElement>()
  private val paint = mutableMapOf<String, JsonElement>()
  private val root = mutableMapOf<String, JsonElement>()

  var minZoom: Float
    get() = (root["minzoom"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
    set(value) {
      setRootProperty("minzoom", JsonPrimitive(value))
    }

  var maxZoom: Float
    get() = (root["maxzoom"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 24f
    set(value) {
      setRootProperty("maxzoom", JsonPrimitive(value))
    }

  var visible: Boolean
    get() = (layout["visibility"] as? JsonPrimitive)?.content != "none"
    set(value) {
      // The style spec has no boolean here; visibility is the string "visible" or "none".
      setLayoutProperty("visibility", JsonPrimitive(if (value) "visible" else "none"))
    }

  protected fun setLayoutProperty(name: String, value: JsonElement) {
    layout[name] = value
  }

  protected fun setPaintProperty(name: String, value: JsonElement) {
    paint[name] = value
  }

  protected fun setLayoutProperty(name: String, value: CompiledExpression<*>) {
    setLayoutProperty(name, value.toStyleJson())
  }

  protected fun setPaintProperty(name: String, value: CompiledExpression<*>) {
    setPaintProperty(name, value.toStyleJson())
  }

  /**
   * Sets the transition of the paint property [property], named without the `-transition` suffix. A
   * null [options] removes the key, which returns the property to the style's global transition.
   */
  protected fun setPaintTransition(property: String, options: TransitionOptions?) {
    val key = property + TRANSITION_SUFFIX
    if (options == null) paint.remove(key) else paint[key] = options.toTransitionJson()
  }

  /**
   * Sets a top-level layer property, by style-spec name. These must be present in the JSON that
   * creates the layer, not pushed afterwards.
   */
  protected fun setRootProperty(name: String, value: JsonElement) {
    root[name] = value
  }

  /**
   * Sets this layer's filter. An unset filter must stay null: MapLibre reads a null filter as
   * "match every feature", and anything else has to be a non-empty array — a scalar `true` fails
   * the whole layer.
   */
  protected fun setFilterExpression(filter: CompiledExpression<*>) {
    setFilterJson(filter.toStyleJson())
  }

  /** Sets this layer's filter from style JSON, for [UnknownLayer]. Same null contract. */
  protected fun setFilterJson(filter: JsonElement) {
    root["filter"] = filter
  }

  /** Unsupported property names and their error messages. */
  private val unsupportedProperties = mutableMapOf<String, String>()

  /**
   * Omits an unsupported [value] and records [reason] for one warning after attachment.
   *
   * A null literal does not set the property and does not produce a warning. Use the loaded style's
   * unsupported-property table when an engine does not implement the property.
   */
  protected fun skipUnsupportedProperty(
    name: String,
    value: CompiledExpression<*>,
    reason: String,
  ) {
    if (value == NullLiteral) return
    unsupportedProperties[name] = reason
  }

  /**
   * Returns the complete layer object defined by the style spec. This omits null-valued properties
   * because the style spec does not permit null property values.
   */
  internal fun toJson(): JsonObject = buildJsonObject {
    // `id` and `type` first: MapLibre reads the type before the properties that depend on it.
    put("id", id)
    put("type", type)
    sourceId?.let { put("source", it) }
    root.forEach { (key, value) -> if (key in ROOT_KEYS && value !is JsonNull) put(key, value) }
    layout
      .filterTo(mutableMapOf()) { (_, value) -> value !is JsonNull }
      .let { if (it.isNotEmpty()) put("layout", JsonObject(it)) }
    paint
      .filterTo(mutableMapOf()) { (_, value) -> value !is JsonNull }
      .let { if (it.isNotEmpty()) put("paint", JsonObject(it)) }
  }

  internal fun definition(): LayerDefinition =
    LayerDefinition(id, type, sourceId, toJson(), unsupportedProperties.toMap())

  override fun toString() = "${this::class.simpleName}(id=\"$id\")"
}
