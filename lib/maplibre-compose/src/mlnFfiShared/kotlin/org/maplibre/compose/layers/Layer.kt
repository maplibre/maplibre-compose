package org.maplibre.compose.layers

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.toFfiJsonValue
import org.maplibre.compose.util.toJsonElement
import org.maplibre.compose.util.toStyleJson
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.map.MapHandle

/** Style JSON keys that live at the top level of a layer rather than in layout or paint. */
private val ROOT_KEYS =
  setOf("id", "type", "source", "source-layer", "minzoom", "maxzoom", "filter")

/**
 * A style layer, as a live descriptor.
 *
 * Before the layer is added to a style, setters accumulate into the descriptor; adding emits one
 * complete layer JSON object; afterwards setters go straight to MapLibre.
 */
internal actual sealed class Layer(actual val id: String) {

  /** The layer's `type` in the style spec, e.g. `fill`. */
  protected abstract val type: String

  /** The source this layer draws from, or null for layers that have none, such as background. */
  protected open val sourceId: String? = null

  /**
   * The source descriptor this layer draws from, when it has one.
   *
   * The C API rejects a layer naming a source that does not exist yet, and Compose adds a layer
   * before the effect that adds its source runs, so [attach] attaches this source first.
   */
  internal open val sourceDescriptor: org.maplibre.compose.sources.Source?
    get() = null

  private val layout = mutableMapOf<String, JsonElement>()
  private val paint = mutableMapOf<String, JsonElement>()
  private val root = mutableMapOf<String, JsonElement>()

  @Volatile
  internal var binding: StyleBinding = StyleBinding.UNLOADED
    private set

  internal val isAttached: Boolean
    get() = binding.isLoaded

  actual var minZoom: Float
    get() = (root["minzoom"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
    set(value) {
      setRootProperty("minzoom", JsonPrimitive(value))
    }

  actual var maxZoom: Float
    get() = (root["maxzoom"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 24f
    set(value) {
      setRootProperty("maxzoom", JsonPrimitive(value))
    }

  actual var visible: Boolean
    get() = (layout["visibility"] as? JsonPrimitive)?.content != "none"
    set(value) {
      // The style spec has no boolean here; visibility is the string "visible" or "none".
      setLayoutProperty("visibility", JsonPrimitive(if (value) "visible" else "none"))
    }

  protected fun setLayoutProperty(name: String, value: JsonElement) {
    layout[name] = value
    pushProperty(name, value)
  }

  protected fun setPaintProperty(name: String, value: JsonElement) {
    paint[name] = value
    pushProperty(name, value)
  }

  protected fun setLayoutProperty(name: String, value: CompiledExpression<*>) {
    setLayoutProperty(name, value.toStyleJson())
  }

  protected fun setPaintProperty(name: String, value: CompiledExpression<*>) {
    setPaintProperty(name, value.toStyleJson())
  }

  /**
   * Sets a top-level layer property, by style-spec name. MapLibre reads these from the layer object
   * itself, so they must be present in the JSON that creates the layer, not pushed afterwards.
   */
  protected fun setRootProperty(name: String, value: JsonElement) {
    root[name] = value
    pushProperty(name, value)
  }

  /**
   * Sets this layer's filter. Filters go through `setLayerFilter` rather than `setLayerProperty`
   * because MapLibre treats the filter as part of the layer rather than as a property of it.
   *
   * An unset filter compiles to a null literal and must stay null: mbgl reads an undefined filter
   * as "match every feature", and anything else has to be a non-empty array — a scalar `true` fails
   * the whole layer with "filter value must be a non empty array".
   */
  protected fun setFilterExpression(filter: CompiledExpression<*>) {
    setFilterJson(filter.toStyleJson())
  }

  /**
   * Sets this layer's filter from style JSON, for [UnknownLayer], which restores a base-style layer
   * from reported JSON and has no [CompiledExpression] behind its filter. Same null contract.
   */
  protected fun setFilterJson(filter: JsonElement) {
    root["filter"] = filter
    binding.mutateMap { map -> map.setLayerFilter(id, filter.toFfiJsonValue()) }
  }

  /**
   * Sends a property to an already-attached layer, logging a value MapLibre rejects rather than
   * throwing: this runs inside a Compose update block, so an escaping exception would kill the
   * composition applying the style. [attach] deliberately does throw instead.
   */
  private fun pushProperty(name: String, value: JsonElement) {
    binding.mutateMap { map ->
      try {
        map.setLayerProperty(id, name, value.toFfiJsonValue())
      } catch (error: MaplibreException) {
        binding.logger?.w(error) {
          "Layer '$id' of type '$type' kept its previous '$name': MapLibre rejected $value."
        }
      }
    }
  }

  /**
   * Style-spec property names this layer was asked for and did not write, with why not. An
   * unattached layer has no logger to report through, so [attach] drains this once it has a style.
   */
  private val unsupportedProperties = mutableMapOf<String, String>()

  /**
   * Drops a property MapLibre Native will not accept, and says so once. Writing it anyway is not an
   * option: MapLibre refuses the entire layer over one unknown property name.
   *
   * @param value the value that was asked for. An unset optional property compiles to a null
   *   literal, which asks for nothing and so is not reported.
   */
  protected fun skipUnsupportedProperty(
    name: String,
    value: CompiledExpression<*>,
    reason: String,
  ) {
    if (value == NullLiteral) return
    if (unsupportedProperties.put(name, reason) != null) return
    if (isAttached) reportUnsupportedProperty(name, reason)
  }

  private fun reportUnsupportedProperty(name: String, reason: String) {
    binding.logger?.w { "Layer '$id' of type '$type' cannot set '$name': $reason" }
  }

  /**
   * The complete layer object, as the style spec defines it.
   *
   * Null-valued properties are omitted: the style spec has no null, and MapLibre rejects the whole
   * layer over one. They are still pushed to an attached layer, where null resets to the default.
   */
  internal fun toJson(): JsonObject = buildJsonObject {
    // `id` and `type` first: MapLibre reads the type before the properties that depend on it.
    put("id", id)
    put("type", type)
    sourceId?.let { put("source", it) }
    root.forEach { (key, value) -> if (key in ROOT_KEYS && value !is JsonNull) put(key, value) }
    layout
      .filterValues { it !is JsonNull }
      .let { if (it.isNotEmpty()) put("layout", JsonObject(it)) }
    paint.filterValues { it !is JsonNull }.let { if (it.isNotEmpty()) put("paint", JsonObject(it)) }
  }

  /**
   * Adds this layer to a style directly below [beforeLayerId], or on top when that is empty:
   * MapLibre has no "add on top" call, and an empty anchor means the same thing.
   */
  internal fun attach(binding: StyleBinding, beforeLayerId: String) {
    check(this.binding === binding || !this.binding.isLoaded) {
      "Layer '$id' already belongs to another loaded style; create a separate layer instance for " +
        "each map"
    }
    // See sourceDescriptor: the source's own effect has not run yet on a fresh style composition.
    // Always ask it to attach: Source verifies exact binding identity even when it is live already.
    sourceDescriptor?.attach(binding)
    val added = binding.mutateMap { map ->
      try {
        map.addStyleLayerJson(toJson().toFfiJsonValue(), beforeLayerId)
      } catch (error: MaplibreException) {
        // Native reports only "layer source does not exist", naming neither.
        throw IllegalStateException(
          "Could not add layer '$id' of type '$type'" +
            (sourceId?.let { " over source '$it'" } ?: "") +
            ": ${error.message}. Layer JSON: ${toJson()}",
          error,
        )
      }
    }
    check(added != null) {
      "Layer '$id' was not added: its style is no longer loaded. It will not appear until the " +
        "style reloads and the composition re-adds it."
    }
    // Published only after native accepted the complete layer definition.
    this.binding = binding
    unsupportedProperties.forEach { (name, reason) -> reportUnsupportedProperty(name, reason) }
  }

  /**
   * Binds this descriptor to a layer already in the style, without adding it. Used when reading
   * back the base style, where adding again would duplicate the layer and change the draw order.
   */
  internal fun bindExisting(binding: StyleBinding) {
    check(this.binding === binding || !this.binding.isLoaded) {
      "Layer '$id' already belongs to another loaded style"
    }
    this.binding = binding
  }

  internal fun detach(expectedBinding: StyleBinding) {
    require(binding === expectedBinding) {
      "Layer '$id' does not belong to the style trying to remove it"
    }
    binding.mutateMap { map -> map.removeStyleLayer(id) }
    binding = StyleBinding.UNLOADED
  }

  /** Moves this layer to sit directly below [beforeLayerId], or on top when that is empty. */
  internal fun moveTo(beforeLayerId: String) {
    binding.mutateMap { map -> map.moveStyleLayer(id, beforeLayerId) }
  }

  /** Reads a property back from the live layer, falling back to the descriptor when detached. */
  protected fun readProperty(name: String): JsonElement =
    binding.readMap { map -> map.layerProperty(id, name)?.toJsonElement() }
      ?: layout[name]
      ?: paint[name]
      ?: root[name]
      ?: JsonNull

  protected fun mutate(update: (map: MapHandle) -> Unit): Boolean =
    binding.mutateMap(update) != null

  override fun toString() = "${this::class.simpleName}(id=\"$id\")"
}
