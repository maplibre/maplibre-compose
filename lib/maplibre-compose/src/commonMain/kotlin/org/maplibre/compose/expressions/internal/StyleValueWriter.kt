package org.maplibre.compose.expressions.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A transport sink; expression-specific normalization belongs to [writeStyleValue]. */
internal interface StyleValueWriter {
  fun nullValue()

  fun booleanValue(value: Boolean)

  fun numberValue(value: Float)

  fun numberText(value: String)

  fun stringValue(value: String)

  fun beginArray()

  fun endArray()

  fun beginObject()

  fun name(value: String)

  fun endObject()

  fun jsonValue(value: JsonElement)
}

internal fun JsonElement.writeJsonValue(writer: StyleValueWriter) {
  when (this) {
    JsonNull -> writer.nullValue()
    is JsonPrimitive ->
      when {
        isString -> writer.stringValue(content)
        booleanOrNull != null -> writer.booleanValue(booleanOrNull!!)
        else -> writer.numberText(content)
      }
    is JsonArray -> {
      writer.beginArray()
      forEach { it.writeJsonValue(writer) }
      writer.endArray()
    }
    is JsonObject -> {
      writer.beginObject()
      forEach { (name, value) ->
        writer.name(name)
        value.writeJsonValue(writer)
      }
      writer.endObject()
    }
  }
}

/** Writes directly to one buffer instead of building strings for every nested array. */
internal class TextStyleValueWriter : StyleValueWriter {
  private val text = StringBuilder()
  private var counts = IntArray(8)
  private var depth = 0
  private var namedValue = false

  val result: String
    get() = text.toString()

  private fun beforeValue() {
    if (namedValue) namedValue = false
    else if (depth > 0) {
      val index = depth - 1
      if (counts[index] > 0) text.append(',')
      counts[index]++
    }
  }

  override fun nullValue() {
    beforeValue()
    text.append("null")
  }

  override fun booleanValue(value: Boolean) {
    beforeValue()
    text.append(value)
  }

  override fun numberValue(value: Float) {
    require(value.isFinite()) { "JSON numbers must be finite: $value" }
    numberText(value.toString())
  }

  override fun numberText(value: String) {
    beforeValue()
    text.append(value)
  }

  override fun stringValue(value: String) {
    beforeValue()
    quoted(value)
  }

  private fun quoted(value: String) {
    text.append('"')
    for (char in value) {
      when (char) {
        '"' -> text.append("\\\"")
        '\\' -> text.append("\\\\")
        '\n' -> text.append("\\n")
        '\r' -> text.append("\\r")
        '\t' -> text.append("\\t")
        '\b' -> text.append("\\b")
        '\u000c' -> text.append("\\f")
        else ->
          if (char < ' ') {
            text.append("\\u00")
            text.append(HexDigits[char.code shr 4])
            text.append(HexDigits[char.code and 15])
          } else text.append(char)
      }
    }
    text.append('"')
  }

  override fun beginArray() {
    beforeValue()
    text.append('[')
    beginContainer()
  }

  override fun endArray() {
    depth--
    text.append(']')
  }

  override fun beginObject() {
    beforeValue()
    text.append('{')
    beginContainer()
  }

  override fun name(value: String) {
    beforeValue()
    quoted(value)
    text.append(':')
    namedValue = true
  }

  override fun endObject() {
    depth--
    text.append('}')
  }

  override fun jsonValue(value: JsonElement) = value.writeJsonValue(this)

  private fun beginContainer() {
    if (depth == counts.size) counts = counts.copyOf(counts.size * 2)
    counts[depth++] = 0
  }

  private companion object {
    const val HexDigits = "0123456789abcdef"
  }
}

/** Used for JSON document assembly and inspection, outside expression property updates. */
internal class JsonStyleValueWriter : StyleValueWriter {
  private sealed interface Container

  private class ArrayContainer(val values: MutableList<JsonElement> = mutableListOf()) : Container

  private class ObjectContainer(
    val values: MutableMap<String, JsonElement> = linkedMapOf(),
    var name: String = "",
  ) : Container

  private val containers = mutableListOf<Container>()
  private var root: JsonElement? = null
  val result: JsonElement
    get() = checkNotNull(root)

  private fun add(value: JsonElement) {
    when (val container = containers.lastOrNull()) {
      null -> root = value
      is ArrayContainer -> container.values.add(value)
      is ObjectContainer -> container.values[container.name] = value
    }
  }

  override fun nullValue() = add(JsonNull)

  override fun booleanValue(value: Boolean) = add(JsonPrimitive(value))

  override fun numberValue(value: Float) = add(JsonPrimitive(value))

  override fun numberText(value: String) =
    add(kotlinx.serialization.json.Json.parseToJsonElement(value))

  override fun stringValue(value: String) = add(JsonPrimitive(value))

  override fun beginArray() {
    containers.add(ArrayContainer())
  }

  override fun endArray() {
    add(JsonArray((containers.removeAt(containers.lastIndex) as ArrayContainer).values))
  }

  override fun beginObject() {
    containers.add(ObjectContainer())
  }

  override fun name(value: String) {
    (containers.last() as ObjectContainer).name = value
  }

  override fun endObject() {
    add(JsonObject((containers.removeAt(containers.lastIndex) as ObjectContainer).values))
  }

  override fun jsonValue(value: JsonElement) = add(value)
}
