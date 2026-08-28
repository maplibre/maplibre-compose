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
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleMutationException
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

  @Volatile
  internal var binding: StyleBinding = StyleBinding.UNLOADED
    private set

  internal val isAttached: Boolean
    get() = binding.isLoaded

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
    pushProperty(name, value, LayerPropertyKind.LAYOUT)
  }

  internal fun setPaintProperty(name: String, value: JsonElement) {
    paint[name] = value
    pushProperty(name, value, LayerPropertyKind.PAINT)
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
    pushProperty(name, value, LayerPropertyKind.ROOT)
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
    binding.setLayerFilter(id, filter)
  }

  /**
   * Logs a value MapLibre rejects rather than throwing: this runs inside a Compose update block,
   * where an escaping exception would kill the composition. [attach] does throw.
   */
  private fun pushProperty(name: String, value: JsonElement, kind: LayerPropertyKind) {
    if (recordIfUnsupported(name, value)) return
    try {
      binding.setLayerProperty(id, name, value, kind)
    } catch (error: StyleMutationException) {
      binding.logger?.w(error) {
        "Layer '$id' of type '$type' kept its previous '$name': MapLibre rejected $value."
      }
    }
  }

  /**
   * Property names requested and not written, each with the reason; [attach] drains this once it
   * can log.
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
    if (unsupportedProperties.put(name, reason) != null) return
    if (isAttached) reportUnsupportedProperty(name, reason)
  }

  private fun recordIfUnsupported(
    name: String,
    value: JsonElement,
    binding: StyleBinding = this.binding,
  ): Boolean {
    val reason = binding.unsupportedLayerPropertyReason(type, name) ?: return false
    if (value is JsonNull) return true
    if (unsupportedProperties.put(name, reason) != null) return true
    if (isAttached) reportUnsupportedProperty(name, reason)
    return true
  }

  private fun reportUnsupportedProperty(name: String, reason: String) {
    binding.logger?.w { "Layer '$id' of type '$type' cannot set '$name': $reason" }
  }

  /**
   * The complete layer object, as the style spec defines it. Null-valued properties are omitted;
   * the spec has no null, and MapLibre rejects the whole layer over one.
   */
  internal fun toJson(binding: StyleBinding = this.binding): JsonObject = buildJsonObject {
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

  /** Adds this layer directly below [beforeLayerId], or on top when that is empty. */
  internal fun attach(binding: StyleBinding, beforeLayerId: String) {
    check(this.binding === binding || !this.binding.isLoaded) {
      "Layer '$id' already belongs to another loaded style; create a separate layer instance for " +
        "each map"
    }
    // A duplicate ID fails here, on the caller, with the message that names the cause; the add
    // itself would fail too, but with MapLibre's generic refusal. Null means the check could not
    // run; the add still refuses a duplicate.
    check(binding.layerExists(id) != true) {
      "Layer ID '$id' is already owned by a different live layer descriptor; create a separate " +
        "layer instance for each map"
    }
    // Buffered properties this engine cannot take stay out of the JSON; the drain below says so.
    layout.forEach { (name, value) -> recordIfUnsupported(name, value, binding) }
    paint.forEach { (name, value) -> recordIfUnsupported(name, value, binding) }
    val added =
      try {
        binding.addLayer(toJson(binding), beforeLayerId)
      } catch (error: StyleMutationException) {
        throw IllegalStateException(
          "Could not add layer '$id' of type '$type'" +
            (sourceId?.let { " over source '$it'" } ?: "") +
            ": ${error.message}. Layer JSON: ${toJson(binding)}",
          error,
        )
      }
    // The style can unload on another thread between the caller's loaded check and the add; the
    // dropped write is the unload contract, not an error.
    if (!added) {
      binding.logger?.w {
        "Layer '$id' was not added: its style unloaded first. It will not appear until the " +
          "style reloads and the composition re-adds it."
      }
      return
    }
    this.binding = binding
    unsupportedProperties.forEach { (name, reason) -> reportUnsupportedProperty(name, reason) }
  }

  /**
   * Binds this descriptor to a layer already in the style, without adding it. Used when reading
   * back the base style.
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
    binding.removeLayer(id)
    binding = StyleBinding.UNLOADED
  }

  /** Reads a property back from the live layer, falling back to the descriptor when detached. */
  internal fun readProperty(name: String): JsonElement =
    binding.layerProperty(id, name) ?: layout[name] ?: paint[name] ?: root[name] ?: JsonNull

  override fun toString() = "${this::class.simpleName}(id=\"$id\")"
}
