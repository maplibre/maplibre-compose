package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.ResolvedLayerDefinition
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.toTransitionJson
import org.maplibre.compose.util.toStyleJson

/** Builds engine-facing snapshots for reconciliation and real-engine tests. */
internal class TestLayer(val id: String, private val type: String, source: Source? = null) {
  private val values =
    mutableMapOf<String, JsonElement>("id" to JsonPrimitive(id), "type" to JsonPrimitive(type))
  var filterUnsupportedProperties: Boolean = false

  init {
    source?.let { values["source"] = JsonPrimitive(it.id) }
  }

  constructor(id: String, value: JsonObject) : this(id, (value["type"] as JsonPrimitive).content) {
    values.putAll(value)
  }

  var minZoom: Float
    get() = (values["minzoom"] as? JsonPrimitive)?.content?.toFloat() ?: 0f
    set(value) {
      root("minzoom", JsonPrimitive(value))
    }

  var maxZoom: Float
    get() = (values["maxzoom"] as? JsonPrimitive)?.content?.toFloat() ?: 24f
    set(value) {
      root("maxzoom", JsonPrimitive(value))
    }

  var sourceLayer: String
    get() = (values["source-layer"] as? JsonPrimitive)?.content.orEmpty()
    set(value) {
      root("source-layer", JsonPrimitive(value))
    }

  var visible: Boolean
    get() =
      ((values["layout"] as? JsonObject)?.get("visibility") as? JsonPrimitive)?.content != "none"
    set(value) {
      layout("visibility", JsonPrimitive(if (value) "visible" else "none"))
    }

  fun root(name: String, value: JsonElement) {
    values[name] = value
  }

  fun root(name: String, value: LayerProperty<*>) = root(name, value.resolve(emptyMap()))

  fun paint(name: String, value: LayerProperty<*>) = paint(name, value.resolve(emptyMap()))

  fun layout(name: String, value: LayerProperty<*>) = layout(name, value.resolve(emptyMap()))

  fun paint(name: String, value: JsonElement) = property("paint", name, value)

  fun layout(name: String, value: JsonElement) = property("layout", name, value)

  fun paintTransition(name: String, options: TransitionOptions?) {
    property("paint", "$name-transition", options?.toTransitionJson() ?: JsonNull)
  }

  private fun property(section: String, name: String, value: JsonElement) {
    val properties = (values[section] as? JsonObject).orEmpty()
    values[section] =
      JsonObject(if (value == JsonNull) properties - name else properties + (name to value))
  }

  fun toJson(): JsonObject = JsonObject(values.filterValues { it != JsonNull })

  fun definition(): ResolvedLayerDefinition =
    ResolvedLayerDefinition(
      id,
      type,
      (values["source"] as? JsonPrimitive)?.content,
      toJson(),
      filterUnsupportedProperties = filterUnsupportedProperties,
    )
}

internal fun <T : ExpressionValue?> CompiledExpression<T>.asLayerProperty(): LayerProperty<T> =
  LayerProperty {
    toStyleJson()
  }
