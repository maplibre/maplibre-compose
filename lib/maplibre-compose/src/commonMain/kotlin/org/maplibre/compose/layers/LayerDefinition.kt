package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.style.StyleProperty
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.toTransitionJson

/**
 * An immutable layer description, independent of a map or loaded style. Create one with
 * [layerDefinition]. Expressions retain their image references until [Layer] prepares them.
 */
public class LayerDefinition
internal constructor(
  public val type: String,
  internal val properties: Map<StyleProperty, LayerValue>,
  internal val filterUnsupportedProperties: Boolean = false,
  internal val json: JsonObject? = null,
  internal val unsupportedProperties: Map<String, String> = emptyMap(),
) {
  override fun equals(other: Any?): Boolean =
    other is LayerDefinition &&
      type == other.type &&
      properties == other.properties &&
      unsupportedProperties == other.unsupportedProperties &&
      json == other.json &&
      filterUnsupportedProperties == other.filterUnsupportedProperties

  override fun hashCode(): Int =
    31 * (31 * type.hashCode() + properties.hashCode()) +
      filterUnsupportedProperties.hashCode() +
      json.hashCode() +
      unsupportedProperties.hashCode()
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

/**
 * Builds a layer of any engine-supported [type]. Property names and raw JSON are passed to the
 * engine without Compose's style-spec filtering. The engine still validates the layer.
 *
 * This describes a complete value: a property omitted from a later description is removed. Paint
 * transition durations, including JSON transition objects, follow the system animation duration
 * scale. Use [RawLayer] to pass JSON timing unchanged.
 */
public fun layerDefinition(
  type: String,
  builder: LayerDefinitionBuilder.() -> Unit = {},
): LayerDefinition {
  require(type.isNotBlank()) { "Layer type must not be blank" }
  return LayerDefinitionBuilder().apply(builder).build(type)
}

/** Declares named properties. Repeating a property replaces its earlier declaration. */
public class LayerDefinitionBuilder internal constructor() {
  private val properties = linkedMapOf<StyleProperty, LayerValue>()
  private val unsupportedProperties = linkedMapOf<String, String>()

  internal fun unsupported(name: String, reason: String) {
    unsupportedProperties[name] = reason
  }

  /** Adds an unchanged JSON root value. Identity and property sections have dedicated APIs. */
  public fun root(name: String, value: JsonElement) {
    put(null, name, LayerValue.Json(value.snapshot()))
  }

  /** Adds an unchanged JSON layout value, including explicit JSON null. */
  public fun layout(name: String, value: JsonElement) {
    put("layout", name, LayerValue.Json(value.snapshot()))
  }

  /**
   * Adds a JSON paint value, including explicit null. Transition timing follows the system duration
   * scale.
   */
  public fun paint(name: String, value: JsonElement) {
    put("paint", name, LayerValue.Json(value.snapshot()))
  }

  /** Adds a root expression. A null expression or null literal leaves the property unset. */
  public fun root(
    name: String,
    value: Expression<*>?,
    units: LayerExpressionContext = DefaultLayerExpressionContext,
  ) {
    put(null, name, LayerValue.Expression(value, units))
  }

  /** Adds a layout expression, with managed images and optional text-unit conversion. */
  public fun layout(
    name: String,
    value: Expression<*>?,
    units: LayerExpressionContext = DefaultLayerExpressionContext,
  ) {
    put("layout", name, LayerValue.Expression(value, units))
  }

  /** Adds a paint expression, with managed images and optional text-unit conversion. */
  public fun paint(
    name: String,
    value: Expression<*>?,
    units: LayerExpressionContext = DefaultLayerExpressionContext,
  ) {
    put("paint", name, LayerValue.Expression(value, units))
  }

  /** Declares transition timing. Null uses the style's global transition. */
  public fun paintTransition(property: String, options: TransitionOptions?) {
    require(property.isNotBlank()) { "Property name must not be blank" }
    val key = StyleProperty("paint", "$property-transition")
    if (options == null) properties.remove(key)
    else properties[key] = LayerValue.Json(options.toTransitionJson())
  }

  private fun put(section: String?, name: String, value: LayerValue) {
    require(name.isNotBlank()) { "Property name must not be blank" }
    require(
      section != null || name != "id" && name != "type" && name != "layout" && name != "paint"
    ) {
      "'$name' is not a root property; use the layer identity or property section API"
    }
    properties[StyleProperty(section, name)] = value
  }

  internal fun build(type: String, filterUnsupportedProperties: Boolean = false): LayerDefinition =
    LayerDefinition(
      type,
      properties.toMap(),
      filterUnsupportedProperties = filterUnsupportedProperties,
      unsupportedProperties = unsupportedProperties.toMap(),
    )
}

internal sealed interface LayerValue {
  data class Json(val value: JsonElement) : LayerValue

  data class Expression(
    val value: org.maplibre.compose.expressions.ast.Expression<*>?,
    val units: LayerExpressionContext,
  ) : LayerValue
}

// Built-in wrappers retain their documented cross-engine property filtering. Generic declarations
// deliberately leave acceptance to the engine, including when used with a standard layer type.
internal fun builtInLayerDefinition(
  type: String,
  builder: LayerDefinitionBuilder.() -> Unit = {},
): LayerDefinition {
  require(type.isNotBlank()) { "Layer type must not be blank" }
  return LayerDefinitionBuilder().apply(builder).build(type, filterUnsupportedProperties = true)
}

// JsonObject/JsonArray may wrap caller-owned mutable collections. Declarations must not change
// behind the reconciler's previous revision when those collections are reused.
internal fun JsonElement.snapshot(): JsonElement =
  when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.snapshot() })
    is JsonArray -> JsonArray(map { it.snapshot() })
    else -> this
  }
