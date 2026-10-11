package org.maplibre.compose.style.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.expressions.internal.StyleValueWriter
import org.maplibre.compose.expressions.internal.writeJsonValue

internal actual class EncodedStyleValue(private val value: dynamic) {
  fun <T> toJsValue(): T = value.unsafeCast<T>()

  actual fun toJson(): String = JSON.stringify(value)

  actual fun toJsonElement(): JsonElement = Json.parseToJsonElement(toJson())

  actual override fun equals(other: Any?): Boolean =
    other is EncodedStyleValue && equalValues(value, other.value)

  actual override fun hashCode(): Int = toJsonElement().hashCode()
}

private fun equalValues(left: dynamic, right: dynamic): Boolean {
  if (js("Object.is(left, right)") as Boolean) return true
  if (left == null || right == null || jsTypeOf(left) != "object" || jsTypeOf(right) != "object")
    return false
  if (js("Array.isArray(left)") != js("Array.isArray(right)")) return false
  val leftKeys: Array<String> = js("Object.keys(left)")
  val rightKeys: Array<String> = js("Object.keys(right)")
  return leftKeys.size == rightKeys.size &&
    leftKeys.all { key ->
      (js("Object.prototype.hasOwnProperty.call(right, key)") as Boolean) &&
        equalValues(left[key], right[key])
    }
}

internal actual fun encodeStyleValue(value: StyleValue): EncodedStyleValue =
  EncodedStyleValue(JsStyleValueWriter().also { value.write(it) }.result)

private class JsStyleValueWriter : StyleValueWriter {
  private val containers = mutableListOf<dynamic>()
  private var propertyName = ""
  var result: dynamic = null
    private set

  private fun add(value: dynamic) {
    if (containers.isEmpty()) result = value
    else {
      val parent = containers.last()
      if (js("Array.isArray(parent)") as Boolean) parent.push(value)
      else parent[propertyName] = value
    }
  }

  override fun nullValue() = add(null)

  override fun booleanValue(value: Boolean) = add(value)

  override fun numberValue(value: Float) {
    require(value.isFinite()) { "JSON numbers must be finite: $value" }
    add(if (value == 0f) 0f else value)
  }

  override fun numberText(value: String) = add(JSON.parse<dynamic>(value))

  override fun stringValue(value: String) = add(value)

  override fun beginArray() {
    val value: dynamic = js("[]")
    add(value)
    containers.add(value)
  }

  override fun endArray() {
    containers.removeAt(containers.lastIndex)
  }

  override fun beginObject() {
    val value: dynamic = js("Object.create(null)")
    add(value)
    containers.add(value)
  }

  override fun name(value: String) {
    propertyName = value
  }

  override fun endObject() {
    containers.removeAt(containers.lastIndex)
  }

  override fun jsonValue(value: JsonElement) = value.writeJsonValue(this)
}
