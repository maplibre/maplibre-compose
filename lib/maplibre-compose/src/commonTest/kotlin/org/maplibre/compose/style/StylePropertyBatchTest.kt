package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.layers.Anchor

class StylePropertyBatchTest {

  @Test
  fun a_layer_update_batches_its_property_writes() = runTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()

    reconciler.apply(style, revision(background("a", "red"), background("b", "blue")))
    reconciler.apply(style, revision(background("a", "green"), background("b", "yellow")))

    // One batch per changed layer, nothing for unchanged definitions.
    assertEquals(
      listOf(
        listOf("a" to "background-color"),
        listOf("b" to "background-color"),
      ),
      style.layerPropertyBatches.map { batch -> batch.map { it.layerId to it.name } },
    )
    assertEquals(JsonPrimitive("green"), style.layerProperty("a", "background-color"))
    assertEquals(JsonPrimitive("yellow"), style.layerProperty("b", "background-color"))

    reconciler.apply(style, revision(background("a", "green"), background("b", "yellow")))
    assertEquals(2, style.layerPropertyBatches.size, "an unchanged revision writes nothing")
  }

  @Test
  fun a_transition_writes_before_the_value_it_times() = runTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()

    reconciler.apply(style, revision(background("a", "red")))
    reconciler.apply(
      style,
      revision(background("a", "green", transition = """{"duration":300.0,"delay":50.0}""")),
    )

    val writes = style.layerPropertyBatches.single().map { it.name }
    assertEquals(
      listOf("background-color-transition", "background-color"),
      writes,
      "a transition must write before the value it times",
    )
  }

  @Test
  fun a_rejected_write_keeps_its_value_without_failing_the_batch() = runTest {
    val style = RecordingStyleBinding(refusedLayerProperties = setOf("a:background-color"))
    val reconciler = StyleReconciler()

    reconciler.apply(style, revision(background("a", "red"), background("b", "blue")))
    reconciler.apply(style, revision(background("a", "green"), background("b", "yellow")))

    assertEquals(JsonPrimitive("red"), style.layerProperty("a", "background-color"))
    assertEquals(JsonPrimitive("yellow"), style.layerProperty("b", "background-color"))
  }

  private fun revision(vararg layers: LayerDefinition): DesiredStyleRevision =
    DesiredStyleRevision(
      sources = emptyList(),
      layers = layers.map { DesiredStyleLayer(it, Anchor.Top, null, null) },
      images = emptyList(),
    )

  private fun background(id: String, color: String, transition: String? = null): LayerDefinition =
    LayerDefinition(
      id = id,
      type = "background",
      sourceId = null,
      value =
        buildJsonObject {
          put("id", JsonPrimitive(id))
          put("type", JsonPrimitive("background"))
          put(
            "paint",
            buildJsonObject {
              put("background-color", JsonPrimitive(color))
              if (transition != null) {
                put("background-color-transition", Json.parseToJsonElement(transition))
              }
            },
          )
        },
    )
}
