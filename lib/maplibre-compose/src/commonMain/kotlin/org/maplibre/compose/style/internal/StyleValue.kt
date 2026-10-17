package org.maplibre.compose.style.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.internal.StyleValueWriter
import org.maplibre.compose.expressions.internal.writeStyleValue

/** Retains compiled expressions until an engine needs its transport representation. */
internal sealed class StyleValue {
  class Json(val value: JsonElement) : StyleValue()

  class Expression(val value: CompiledExpression<*>) : StyleValue()

  class Object(val values: Map<String, StyleValue>) : StyleValue()

  val encoded: EncodedStyleValue by lazy { encodeStyleValue(this) }
  val json: JsonElement by lazy {
    when (this) {
      is Json -> value
      is Object -> JsonObject(values.mapValues { it.value.json })
      is Expression -> encoded.toJsonElement()
    }
  }

  val isNull: Boolean
    get() =
      when (this) {
        is Json -> value == JsonNull
        is Expression -> value == NullLiteral
        is Object -> false
      }

  val objectValues: Map<String, StyleValue>?
    get() =
      when (this) {
        is Object -> values
        is Json -> (value as? JsonObject)?.mapValues { Json(it.value) }
        is Expression -> null
      }

  fun write(writer: StyleValueWriter) {
    when (this) {
      is Json -> writer.jsonValue(value)
      is Expression -> value.writeStyleValue(writer)
      is Object -> {
        writer.beginObject()
        values.forEach { (name, value) ->
          writer.name(name)
          value.write(writer)
        }
        writer.endObject()
      }
    }
  }

  final override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is StyleValue) return false
    return when {
      this is Json && other is Json -> value == other.value
      this is Object && other is Object -> values == other.values
      this is Expression && other is Expression -> value == other.value || encoded == other.encoded
      else -> json == other.json
    }
  }

  final override fun hashCode(): Int = json.hashCode()

  final override fun toString(): String = encoded.toJson()
}

/** The transport is JSON text on Native, and ordinary JavaScript values in the browser. */
internal expect class EncodedStyleValue {
  fun toJson(): String

  fun toJsonElement(): JsonElement

  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int
}

internal expect fun encodeStyleValue(value: StyleValue): EncodedStyleValue
