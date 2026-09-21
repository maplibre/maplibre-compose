package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerProperty

class LayerNodeTest {
  @Test
  fun updates_reuse_unchanged_sections_without_mutating_previous_snapshots() {
    val color = StyleProperty("paint", "color")
    val visibility = StyleProperty("layout", "visibility")
    val initial =
      PreparedLayerDefinition(
        "layer",
        "plugin",
        properties = mapOf(color to JsonPrimitive("red"), visibility to JsonPrimitive("visible")),
      )
    val node = LayerNode(initial, Anchor.Top)
    val first = node.definition
    node.updateDefinition(initial.copy(properties = initial.properties.toMap()))
    assertSame(first, node.definition)

    val changed = initial.copy(properties = initial.properties + (color to JsonPrimitive("blue")))
    node.updateDefinition(changed)
    val second = node.definition
    assertSame(first.value["layout"], second.value["layout"])
    assertEquals(JsonPrimitive("red"), (first.value["paint"] as JsonObject)["color"])
    assertEquals(JsonPrimitive("blue"), (second.value["paint"] as JsonObject)["color"])

    node.updateDefinition(changed.copy(properties = mapOf(visibility to JsonPrimitive("visible"))))
    assertFalse(node.definition.value.containsKey("paint"))
    assertSame(second.value["layout"], node.definition.value["layout"])
    assertEquals(JsonPrimitive("blue"), (second.value["paint"] as JsonObject)["color"])
  }

  @Test
  fun switching_between_raw_and_prepared_values_preserves_nulls_and_empty_sections() {
    val raw = buildJsonObject {
      put("extension", JsonNull)
      put("layout", JsonObject(emptyMap()))
      put("paint", buildJsonObject { put("color", JsonNull) })
    }
    val initial = PreparedLayerDefinition("layer", "plugin", sourceId = "managed", json = raw)
    val node = LayerNode(initial, Anchor.Top)
    val first = node.definition
    assertFalse(first.scaleTransitions)
    assertSame(raw["layout"], first.value["layout"])
    assertEquals(JsonPrimitive("managed"), first.value["source"])
    assertEquals(JsonNull, first.value["extension"])

    val typed =
      initial.copy(
        json = null,
        properties = mapOf(StyleProperty("paint", "size") to JsonPrimitive(2)),
      )
    node.updateDefinition(typed)
    assertTrue(node.definition.scaleTransitions)
    assertFalse(node.definition.value.containsKey("extension"))
    assertFalse(node.definition.value.containsKey("layout"))
    assertEquals(buildJsonObject { put("size", 2) }, node.definition.value["paint"])

    node.updateDefinition(initial.copy(sourceId = null))
    assertFalse(node.definition.value.containsKey("source"))
    assertSame(raw["paint"], node.definition.value["paint"])
    assertSame(raw["layout"], node.definition.value["layout"])
    assertEquals(JsonPrimitive("managed"), first.value["source"])
  }

  @Test
  fun image_changes_reuse_json_but_metadata_changes_update_the_definition() {
    val path = StyleProperty("layout", "image")
    val initial = PreparedLayerDefinition("layer", "plugin")
    val literal = initial.copy(properties = mapOf(path to JsonPrimitive("sprite")))
    val node = LayerNode(literal, Anchor.Top)
    val withLiteral = node.definition
    val image = LayerProperty<StringValue> { JsonPrimitive("image-id") }
    val images = mapOf(path to image)
    node.updateDefinition(initial.copy(imageProperties = images))
    val first = node.definition
    assertFalse(first.value.containsKey("layout"))
    assertEquals(JsonPrimitive("sprite"), (withLiteral.value["layout"] as JsonObject)["image"])
    node.updateDefinition(initial.copy(imageProperties = images.toMap()))
    assertSame(first, node.definition)
    assertSame(images, node.imageProperties)

    node.updateDefinition(initial)
    assertSame(first, node.definition)
    assertTrue(node.imageProperties.isEmpty())
    node.updateDefinition(initial.copy(filterUnsupportedProperties = true))
    assertTrue(node.definition.filterUnsupportedProperties)
    assertSame(first.value, node.definition.value)
    assertTrue(node.imageProperties.isEmpty())
  }
}
