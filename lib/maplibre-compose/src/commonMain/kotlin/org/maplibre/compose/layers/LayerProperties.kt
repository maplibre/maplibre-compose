package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.style.ResolvedLayerDefinition
import org.maplibre.compose.style.StyleProperty
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.toTransitionJson

/**
 * Properties of one [Layer]. Each invocation describes the complete layer: omitted properties are
 * removed, and repeating a property replaces its earlier value. Use ordinary receiver extensions to
 * share groups of properties between layers.
 *
 * Expressions support painters, bitmaps, and text units. JSON values may include explicit nulls.
 * The engine validates property names and values. This receiver is valid only during the [Layer]
 * properties block.
 */
public class LayerProperties internal constructor(private val cache: LayerPropertyCache) {
  private val root = linkedMapOf<String, JsonElement>()
  private val layout = linkedMapOf<String, JsonElement>()
  private val paint = linkedMapOf<String, JsonElement>()
  private var images: MutableMap<StyleProperty, LayerProperty<*>>? = null
  private var unsupported: MutableMap<String, String>? = null
  private var open = true

  /**
   * Adds a root JSON value. Use [Layer] for `id` and `type`, and [layout] or [paint] for
   * properties.
   */
  public fun root(name: String, value: JsonElement) {
    putJson(null, name, value)
  }

  /** Adds a layout JSON value, including explicit null. */
  public fun layout(name: String, value: JsonElement) {
    putJson("layout", name, value)
  }

  /** Adds a paint JSON value. Transition timing follows the system animation-duration scale. */
  public fun paint(name: String, value: JsonElement) {
    putJson("paint", name, value)
  }

  /** Adds a root expression. A null expression or null literal leaves the property unset. */
  public fun root(
    name: String,
    value: Expression<*>?,
    units: LayerExpressionContext = DefaultLayerExpressionContext,
  ) {
    putExpression(null, name, value, units)
  }

  /** Adds a layout expression, with managed images and optional text-unit conversion. */
  public fun layout(
    name: String,
    value: Expression<*>?,
    units: LayerExpressionContext = DefaultLayerExpressionContext,
  ) {
    putExpression("layout", name, value, units)
  }

  /** Adds a paint expression, with managed images and optional text-unit conversion. */
  public fun paint(
    name: String,
    value: Expression<*>?,
    units: LayerExpressionContext = DefaultLayerExpressionContext,
  ) {
    putExpression("paint", name, value, units)
  }

  /**
   * Declares transition timing. Null removes a previous declaration and uses the global transition.
   */
  public fun paintTransition(property: String, options: TransitionOptions?) {
    validate("paint", property)
    val name = "$property-transition"
    if (options != null) putJson("paint", name, options.toTransitionJson())
    else {
      paint.remove(name)
      cache.paint[name]?.let { images?.remove(it.path) }
    }
  }

  private fun validate(section: String?, name: String) {
    check(open) { "LayerProperties is only valid during its properties block" }
    require(name.isNotBlank()) { "Property name must not be blank" }
    require(
      section != null || name != "id" && name != "type" && name != "layout" && name != "paint"
    ) {
      "'$name' is not a root property; use the layer identity or property section API"
    }
  }

  private fun values(section: String?): MutableMap<String, JsonElement> =
    when (section) {
      "layout" -> layout
      "paint" -> paint
      else -> root
    }

  private fun putJson(section: String?, name: String, value: JsonElement) {
    validate(section, name)
    values(section)[name] = value.snapshot()
    cache.entries(section)[name]?.let { images?.remove(it.path) }
  }

  private fun putExpression(
    section: String?,
    name: String,
    value: Expression<*>?,
    units: LayerExpressionContext,
  ) {
    validate(section, name)
    val entry = cache.compile(section, name, value, units)
    val target = values(section)
    if (entry.value == null) target.remove(name) else target[name] = entry.value
    if (entry.property.images.isNotEmpty()) {
      val requests = images ?: mutableMapOf<StyleProperty, LayerProperty<*>>().also { images = it }
      requests[entry.path] = entry.property
    } else images?.remove(entry.path)
  }

  internal fun unsupported(name: String, reason: String) {
    check(open)
    val reasons = unsupported ?: mutableMapOf<String, String>().also { unsupported = it }
    reasons[name] = reason
  }

  internal fun finish(
    id: String,
    type: String,
    managedSourceId: String?,
    filterUnsupportedProperties: Boolean,
  ): LayerPropertySnapshot {
    check(open)
    close()
    if (layout.isNotEmpty()) root["layout"] = JsonObject(layout)
    if (paint.isNotEmpty()) root["paint"] = JsonObject(paint)
    val sourceId = layerSourceId(root, managedSourceId)
    root["id"] = JsonPrimitive(id)
    root["type"] = JsonPrimitive(type)
    if (sourceId != null) root["source"] = JsonPrimitive(sourceId)
    return LayerPropertySnapshot(
      ResolvedLayerDefinition(
        id,
        type,
        sourceId,
        JsonObject(root),
        unsupported.orEmpty(),
        filterUnsupportedProperties,
      ),
      images.orEmpty(),
    )
  }

  internal fun close() {
    open = false
  }
}

/**
 * Conversion factors for text units in one property. A null factor leaves that unit unscaled;
 * mixing EM and SP then requires explicit factors. Factors may themselves be expressions.
 *
 * [spScale] also determines conversion of DP text offsets using the map's font scale.
 */
public data class LayerExpressionContext(
  public val emScale: Expression<FloatValue>? = null,
  public val spScale: Expression<FloatValue>? = null,
)

internal val DefaultLayerExpressionContext = LayerExpressionContext()

internal class LayerPropertySnapshot(
  val definition: ResolvedLayerDefinition,
  val images: Map<StyleProperty, LayerProperty<*>>,
)

// Cache only pure compilation results. Abandoned composition may change this cache, but each
// invocation builds its own immutable output exclusively from that invocation's property calls.
internal class LayerPropertyCache(private val compiler: LayerPropertyCompiler) {
  class Entry(
    val path: StyleProperty,
    val expression: Expression<*>?,
    val units: LayerExpressionContext,
    val property: LayerProperty<*>,
  ) {
    val value =
      if (property.images.isEmpty()) property.resolve(emptyMap()).takeUnless { it == JsonNull }
      else null
    var visited = false
  }

  val root = mutableMapOf<String, Entry>()
  val layout = mutableMapOf<String, Entry>()
  val paint = mutableMapOf<String, Entry>()
  private val sections = listOf(root, layout, paint)

  fun begin() {
    sections.forEach { section -> section.values.forEach { it.visited = false } }
  }

  fun end() {
    sections.forEach { section -> section.entries.removeAll { !it.value.visited } }
  }

  fun entries(section: String?): MutableMap<String, Entry> =
    when (section) {
      "layout" -> layout
      "paint" -> paint
      else -> root
    }

  fun compile(
    section: String?,
    name: String,
    expression: Expression<*>?,
    units: LayerExpressionContext,
  ): Entry {
    val entries = entries(section)
    val previous = entries[name]
    val entry =
      if (previous != null && previous.expression == expression && previous.units == units) previous
      else
        Entry(
            previous?.path ?: StyleProperty(section, name),
            expression,
            units,
            compiler.compile(expression, units),
          )
          .also { entries[name] = it }
    entry.visited = true
    return entry
  }
}

// JsonObject/JsonArray may wrap caller-owned mutable collections.
internal fun JsonElement.snapshot(): JsonElement =
  when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.snapshot() })
    is JsonArray -> JsonArray(map { it.snapshot() })
    else -> this
  }
