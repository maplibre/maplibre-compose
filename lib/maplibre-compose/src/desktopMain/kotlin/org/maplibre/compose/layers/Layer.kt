package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.CompiledExpression
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
 * Mirrors [org.maplibre.compose.sources.Source]: before the layer is added to a style, setters
 * accumulate into the descriptor; adding emits one complete layer JSON object; afterwards setters
 * go straight to MapLibre through `setLayerProperty` and `setLayerFilter`.
 *
 * Accumulating first is what makes a layer addable at any point in a composition. Emitting the
 * whole object at once also avoids a partially-configured layer ever being visible, which is what
 * happens if properties are set one at a time after adding.
 */
internal actual sealed class Layer(actual val id: String) {

  /** The layer's `type` in the style spec, e.g. `fill`. */
  protected abstract val type: String

  /** The source this layer draws from, or null for layers that have none, such as background. */
  protected open val sourceId: String? = null

  /**
   * The source descriptor this layer draws from, when it has one.
   *
   * Needed because Compose adds a layer to the style before the effect that adds its source runs:
   * the applier inserts nodes and calls `onEndChanges` — which is where layers reach MapLibre — and
   * only afterwards dispatches remember-observers, where `SourceReferenceEffect` lives. MapLibre's
   * mobile SDKs tolerate a layer naming a source that does not exist yet; the C API rejects it
   * outright, so the layer attaches its source first.
   */
  internal open val sourceDescriptor: org.maplibre.compose.sources.Source?
    get() = null

  private val layout = mutableMapOf<String, JsonElement>()
  private val paint = mutableMapOf<String, JsonElement>()
  private val root = mutableMapOf<String, JsonElement>()

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

  /** Sets a layout property, by style-spec name. */
  protected fun setLayoutProperty(name: String, value: JsonElement) {
    layout[name] = value
    pushProperty(name, value)
  }

  /** Sets a paint property, by style-spec name. */
  protected fun setPaintProperty(name: String, value: JsonElement) {
    paint[name] = value
    pushProperty(name, value)
  }

  /** Sets a layout property from a compiled expression. */
  protected fun setLayoutProperty(name: String, value: CompiledExpression<*>) {
    setLayoutProperty(name, value.toStyleJson())
  }

  /** Sets a paint property from a compiled expression. */
  protected fun setPaintProperty(name: String, value: CompiledExpression<*>) {
    setPaintProperty(name, value.toStyleJson())
  }

  /**
   * Sets a top-level layer property, by style-spec name.
   *
   * Distinct from layout and paint because MapLibre reads these from the layer object itself; they
   * have to be present in the JSON that creates the layer, not pushed afterwards.
   */
  protected fun setRootProperty(name: String, value: JsonElement) {
    root[name] = value
    pushProperty(name, value)
  }

  /**
   * Sets this layer's filter.
   *
   * Filters have their own entry point rather than going through `setLayerProperty`, because
   * MapLibre treats the filter as part of the layer rather than as a property of it.
   *
   * An unset filter compiles to a null literal, which is correct and must stay null: mbgl reads an
   * undefined filter as "match every feature", and anything else has to be a non-empty array. A
   * scalar `true` looks like the obvious way to say "no filter" and is rejected outright — the
   * whole layer fails to add with "filter value must be a non empty array". [toJson] drops the key
   * when it is null, and a null pushed to an already-attached layer clears its filter.
   */
  protected fun setFilterExpression(filter: CompiledExpression<*>) {
    val json = filter.toStyleJson()
    root["filter"] = json
    binding.withMap { map -> map.setLayerFilter(id, json.toFfiJsonValue()) }
  }

  private fun pushProperty(name: String, value: JsonElement) {
    binding.withMap { map -> map.setLayerProperty(id, name, value.toFfiJsonValue()) }
  }

  /**
   * The complete layer object, as the style spec defines it.
   *
   * Null-valued properties are omitted rather than written. An unset optional property compiles to
   * a null literal, but the style spec has no null: MapLibre rejects the whole layer with "layer
   * doesn't support this property" rather than treating it as absent. They are still pushed to an
   * already-attached layer, where null is how a property is reset to its default.
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
   * Adds this layer to a style directly below [beforeLayerId], or on top when that is empty.
   *
   * MapLibre has no "add on top" call; an empty anchor means the same thing, which is what the
   * common [LayerManager] relies on for its append case.
   *
   * TODO(maplibre-native-ffi): use a typed adder once one exists for these layers. The FFI offers
   *   only `addColorReliefLayer`, `addHillshadeLayer`, and `addLocationIndicatorLayer` — nothing
   *   for fill, line, circle, symbol, raster, heatmap, fill-extrusion, or background — so the
   *   generic JSON entry point is the only way to add any layer this library supports. Property
   *   updates already use `setLayerProperty`, which is the shape Android uses.
   */
  internal fun attach(binding: StyleBinding, beforeLayerId: String) {
    this.binding = binding
    // See sourceDescriptor: the source's own effect has not run yet on a fresh style composition.
    sourceDescriptor?.let { source -> if (!source.isAttached) source.attach(binding) }
    val added = binding.withMap { map ->
      try {
        map.addStyleLayerJson(toJson().toFfiJsonValue(), beforeLayerId)
      } catch (error: MaplibreException) {
        // Rethrown with the layer and its source named. Native reports only "layer source does not
        // exist", which does not say which layer or which source, and letting it escape kills the
        // Compose thread that was applying style content.
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
  }

  /**
   * Binds this descriptor to a layer that is already in the style, without adding it.
   *
   * Used when reading back the base style: those layers already exist in MapLibre, so adding them
   * again would duplicate them and change the draw order.
   */
  internal fun bindExisting(binding: StyleBinding) {
    this.binding = binding
  }

  internal fun detach() {
    binding.withMap { map -> map.removeStyleLayer(id) }
    binding = StyleBinding.UNLOADED
  }

  /** Moves this layer to sit directly below [beforeLayerId], or on top when that is empty. */
  internal fun moveTo(beforeLayerId: String) {
    binding.withMap { map -> map.moveStyleLayer(id, beforeLayerId) }
  }

  /** Reads a property back from the live layer, falling back to the descriptor when detached. */
  protected fun readProperty(name: String): JsonElement =
    binding.withMap { map -> map.layerProperty(id, name)?.toJsonElement() }
      ?: layout[name]
      ?: paint[name]
      ?: root[name]
      ?: JsonNull

  protected fun mutate(update: (map: MapHandle) -> Unit): Boolean = binding.withMap(update) != null

  override fun toString() = "${this::class.simpleName}(id=\"$id\")"
}
