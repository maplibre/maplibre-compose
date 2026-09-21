package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.style.StyleProperty

class LayerDefinitionTest {
  @Test
  fun definitions_snapshot_builder_and_nested_json_values() {
    val nested = mutableMapOf<String, JsonElement>("mode" to JsonPrimitive("first"))
    lateinit var builder: LayerDefinitionBuilder
    val definition =
      layerDefinition("plugin") {
        builder = this
        root("options", JsonObject(nested))
      }
    nested["mode"] = JsonPrimitive("changed")
    builder.root("options", JsonPrimitive("replaced"))
    assertEquals(
      LayerValue.Json(JsonObject(mapOf("mode" to JsonPrimitive("first")))),
      definition.properties[StyleProperty(null, "options")],
    )
  }

  @Test
  fun identity_and_sections_cannot_be_overridden_as_root_properties() {
    listOf("id", "type", "paint", "layout").forEach { reserved ->
      assertFailsWith<IllegalArgumentException> {
        layerDefinition("plugin") { root(reserved, JsonPrimitive("wrong")) }
      }
    }
    assertFailsWith<IllegalArgumentException> { layerDefinition("") }
    assertFailsWith<IllegalArgumentException> {
      layerDefinition("plugin") { paintTransition("", null) }
    }
  }
}
