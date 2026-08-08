package org.maplibre.compose.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.nativeffi.json.JsonValue

class JsonConversionsTest {

  private fun roundTrip(json: String) {
    val element = Json.parseToJsonElement(json)
    assertEquals(element, element.toFfiJsonValue().toJsonElement(), "round trip of $json")
  }

  @Test
  fun round_trips_the_shapes_a_style_is_made_of() {
    roundTrip("null")
    roundTrip("true")
    roundTrip("42")
    roundTrip("-17")
    roundTrip("3.5")
    roundTrip("\"circle\"")
    roundTrip("[]")
    roundTrip("{}")
    roundTrip("""["==", ["get", "class"], "park"]""")
    roundTrip("""{"id":"water","type":"fill","source":"osm","paint":{"fill-color":"#0000ff"}}""")
    roundTrip("""["interpolate",["linear"],["zoom"],5,["literal",[1,2]],10,["literal",[3,4]]]""")
  }

  @Test
  fun keeps_integers_integral() {
    // MapLibre distinguishes 5 from 5.0 for some properties.
    assertIs<JsonValue.Int>(JsonPrimitive(5).toFfiJsonValue())
    assertIs<JsonValue.DoubleValue>(JsonPrimitive(5.5).toFfiJsonValue())
    assertEquals(JsonPrimitive(5), JsonValue.Int(5).toJsonElement())
  }

  @Test
  fun distinguishes_a_numeric_string_from_a_number() {
    assertIs<JsonValue.StringValue>(JsonPrimitive("5").toFfiJsonValue())
    assertIs<JsonValue.Int>(JsonPrimitive(5).toFfiJsonValue())
    assertIs<JsonValue.StringValue>(JsonPrimitive("true").toFfiJsonValue())
    assertIs<JsonValue.Bool>(JsonPrimitive(true).toFfiJsonValue())
  }

  @Test
  fun reads_an_unsigned_integer_past_long_max_value_as_unsigned() {
    // The C ABI carries uint64_t in a Long's bit pattern, so the top of the range comes back
    // negative unless it is reinterpreted.
    val element = JsonValue.UInt(-1L).toJsonElement()
    assertEquals("18446744073709551615", element.toString())
  }

  @Test
  fun writes_an_unsigned_integer_past_long_max_value_as_unsigned() {
    val value =
      assertIs<JsonValue.UInt>(Json.parseToJsonElement("18446744073709551615").toFfiJsonValue())

    assertEquals(-1L, value.value)
    assertEquals("18446744073709551615", value.toJsonElement().toString())
  }

  @Test
  fun preserves_object_key_order() {
    // MapLibre reads `type` before the properties that depend on it.
    val json = buildJsonObject {
      put("id", "a")
      put("type", "fill")
      put("source", "s")
    }
    val members = assertIs<JsonValue.ObjectValue>(json.toFfiJsonValue()).members
    assertEquals(listOf("id", "type", "source"), members.map { it.key })
  }

  @Test
  fun preserves_nesting_and_array_order() {
    val json = buildJsonArray {
      add(JsonPrimitive("zoom"))
      add(buildJsonArray { add(JsonPrimitive(1)) })
      add(JsonNull)
    }
    val values = assertIs<JsonValue.Array>(json.toFfiJsonValue()).values
    assertEquals(3, values.size)
    assertEquals(JsonValue.StringValue("zoom"), values[0])
    assertIs<JsonValue.Array>(values[1])
    assertEquals(JsonValue.Null, values[2])
  }
}
