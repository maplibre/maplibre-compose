package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.toStyleJson

/** Style JSON keys that live at the top level of a layer rather than in layout or paint. */
private val ROOT_KEYS =
  setOf("id", "type", "source", "source-layer", "minzoom", "maxzoom", "filter")

internal sealed class Layer(val id: String) {

  /** The layer's `type` in the style spec, e.g. `fill`. */
  internal abstract val type: String

  /** The source this layer draws from, or null for layers that have none, such as background. */
  internal open val sourceId: String? = null

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

  internal fun setLayoutProperty(name: String, value: JsonElement) {
    layout[name] = value
  }

  internal fun setPaintProperty(name: String, value: JsonElement) {
    paint[name] = value
  }

  protected fun setLayoutProperty(name: String, value: CompiledExpression<*>) {
    setLayoutProperty(name, value.toStyleJson())
  }

  protected fun setPaintProperty(name: String, value: CompiledExpression<*>) {
    setPaintProperty(name, value.toStyleJson())
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

  /** Sets this layer's filter from style JSON, for [UnknownLayerDescriptor]. Same null contract. */
  internal fun setFilterJson(filter: JsonElement) {
    root["filter"] = filter
  }

  /**
   * Property names requested and not written, each with the reason; [applyProperties] reports them
   * once it can log.
   */
  private val unsupportedProperties = mutableMapOf<String, String>()

  /**
   * Drops a value MapLibre will not accept for a property it otherwise supports, and says so once.
   * Properties an engine lacks outright belong in the binding's
   * [unsupportedLayerPropertyReason][StyleBinding.unsupportedLayerPropertyReason] table instead.
   *
   * @param value the value that was asked for. A null literal asks for nothing and is not reported.
   */
  protected fun skipUnsupportedProperty(
    name: String,
    value: CompiledExpression<*>,
    reason: String,
  ) {
    if (value == NullLiteral) return
    unsupportedProperties.put(name, reason)
  }

  /**
   * The complete layer object, as the style spec defines it. Null-valued properties are omitted;
   * the spec has no null, and MapLibre rejects the whole layer over one.
   */
  internal fun toJson(binding: StyleBinding = StyleBinding.UNLOADED): JsonObject = buildJsonObject {
    fun accepted(name: String, value: JsonElement) =
      value !is JsonNull && binding.unsupportedLayerPropertyReason(type, name) == null

    // `id` and `type` first: MapLibre reads the type before the properties that depend on it.
    put("id", id)
    put("type", type)
    sourceId?.let { put("source", it) }
    root.forEach { (key, value) -> if (key in ROOT_KEYS && value !is JsonNull) put(key, value) }
    layout
      .filterTo(mutableMapOf()) { (key, value) -> accepted(key, value) }
      .let { if (it.isNotEmpty()) put("layout", JsonObject(it)) }
    paint
      .filterTo(mutableMapOf()) { (key, value) -> accepted(key, value) }
      .let { if (it.isNotEmpty()) put("paint", JsonObject(it)) }
  }

  /**
   * Writes this definition's properties onto a live layer by id. The first add already includes
   * them in JSON; later composition updates dump the maps as [MapState] commands' adapter work.
   */
  internal fun applyProperties(binding: StyleBinding) {
    if (!binding.isLoaded) return
    layout.forEach { (name, value) ->
      writeProperty(binding, name, value, LayerPropertyKind.LAYOUT)
    }
    paint.forEach { (name, value) -> writeProperty(binding, name, value, LayerPropertyKind.PAINT) }
    root.forEach { (name, value) ->
      if (name == "filter") {
        binding.setLayerFilter(id, value)
      } else if (name in ROOT_KEYS && name != "id" && name != "type" && name != "source") {
        writeProperty(binding, name, value, LayerPropertyKind.ROOT)
      }
    }
    unsupportedProperties.forEach { (name, reason) ->
      binding.logger?.w { "Layer '$id' of type '$type' cannot set '$name': $reason" }
    }
  }

  private fun writeProperty(
    binding: StyleBinding,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    val reason = binding.unsupportedLayerPropertyReason(type, name)
    if (reason != null) {
      if (value !is JsonNull) unsupportedProperties.put(name, reason)
      return
    }
    try {
      binding.setLayerProperty(id, name, value, kind)
    } catch (_: org.maplibre.compose.style.StyleMutationException) {
      binding.logger?.w {
        "Layer '$id' of type '$type' kept its previous '$name': MapLibre rejected $value."
      }
    }
  }

  /** Reads a property from the definition. Live reads go through [MapState.liveLayerProperty]. */
  internal fun readProperty(name: String): JsonElement =
    layout[name] ?: paint[name] ?: root[name] ?: JsonNull

  override fun toString() = "${this::class.simpleName}(id=\"$id\")"
}
