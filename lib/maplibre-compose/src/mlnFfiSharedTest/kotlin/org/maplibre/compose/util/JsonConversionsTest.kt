package org.maplibre.compose.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JsonConversionsTest {

  private fun roundTrip(json: String) {
    val element = Json.parseToJsonElement(json)
    assertEquals(element, element.toJsonBytes().toJsonElement(), "round trip of $json")
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
    assertEquals("5", JsonPrimitive(5).toJsonBytes().decodeToString())
    assertEquals("5.5", JsonPrimitive(5.5).toJsonBytes().decodeToString())
  }

  @Test
  fun distinguishes_a_numeric_string_from_a_number() {
    assertEquals("\"5\"", JsonPrimitive("5").toJsonBytes().decodeToString())
    assertEquals("5", JsonPrimitive(5).toJsonBytes().decodeToString())
    assertEquals("\"true\"", JsonPrimitive("true").toJsonBytes().decodeToString())
    assertEquals("true", JsonPrimitive(true).toJsonBytes().decodeToString())
  }

  @Test
  fun preserves_object_key_order() {
    // MapLibre reads `type` before the properties that depend on it.
    val json = buildJsonObject {
      put("id", "a")
      put("type", "fill")
      put("source", "s")
    }
    val roundTripped = json.toJsonBytes().toJsonElement() as JsonObject
    assertEquals(listOf("id", "type", "source"), roundTripped.keys.toList())
  }

  @Test
  fun preserves_nesting_and_array_order() {
    val json = buildJsonArray {
      add(JsonPrimitive("zoom"))
      add(buildJsonArray { add(JsonPrimitive(1)) })
      add(JsonNull)
    }
    val values = json.toJsonBytes().toJsonElement() as JsonArray
    assertEquals(3, values.size)
    assertEquals("zoom", values[0].jsonPrimitive.content)
    assertEquals(JsonArray(listOf(JsonPrimitive(1))), values[1])
    assertEquals(JsonNull, values[2])
  }
}
