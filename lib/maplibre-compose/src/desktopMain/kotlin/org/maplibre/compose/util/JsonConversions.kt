package org.maplibre.compose.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.maplibre.nativeffi.json.JsonValue

/** Conversions between kotlinx-serialization JSON and the FFI's JSON tree. */

/** Converts an FFI value to kotlinx JSON. */
internal fun JsonValue.toJsonElement(): JsonElement =
  when (this) {
    is JsonValue.Null -> JsonNull
    is JsonValue.Bool -> JsonPrimitive(value)
    is JsonValue.Int -> JsonPrimitive(value)
    // The C ABI carries this as uint64_t in a Long's bit pattern, so a value past Long.MAX_VALUE
    // reads back negative unless it is reinterpreted as unsigned.
    is JsonValue.UInt -> JsonPrimitive(value.toULong())
    is JsonValue.DoubleValue -> JsonPrimitive(value)
    is JsonValue.StringValue -> JsonPrimitive(value)
    is JsonValue.Array -> JsonArray(values.map { it.toJsonElement() })
    is JsonValue.ObjectValue -> JsonObject(members.associate { it.key to it.value.toJsonElement() })
    is JsonValue.Unknown ->
      throw IllegalArgumentException(
        "MapLibre returned a JSON value this binding does not understand " +
          "(rawType=$rawType, rawSize=$rawSize). This usually means the FFI is newer than " +
          "MapLibre Compose expects."
      )
  }

/** Converts kotlinx JSON to an FFI value. */
internal fun JsonElement.toFfiJsonValue(): JsonValue =
  when (this) {
    is JsonNull -> JsonValue.Null
    is JsonPrimitive -> toFfiJsonValue()
    is JsonArray -> JsonValue.Array(map { it.toFfiJsonValue() })
    is JsonObject ->
      JsonValue.ObjectValue(entries.map { JsonValue.Member(it.key, it.value.toFfiJsonValue()) })
  }

private fun JsonPrimitive.toFfiJsonValue(): JsonValue {
  if (isString) return JsonValue.StringValue(content)
  booleanOrNull?.let {
    return JsonValue.Bool(it)
  }
  // Integers before doubles: MapLibre distinguishes them, and a zoom level or index arriving as
  // 5.0 instead of 5 changes how some style properties are interpreted.
  longOrNull?.let {
    return JsonValue.Int(it)
  }
  doubleOrNull?.let {
    return JsonValue.DoubleValue(it)
  }
  return JsonValue.StringValue(content)
}
