package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    reconciler.apply(
      style,
      revision(background("a", "red", opacity = 0.5), background("b", "blue")),
    )
    reconciler.apply(
      style,
      revision(background("a", "green", opacity = 0.75), background("b", "yellow")),
    )

    assertEquals(
      setOf("a" to "background-color", "a" to "background-opacity", "b" to "background-color"),
      style.layerPropertyBatches.flatten().map { it.layerId to it.name }.toSet(),
    )
    assertTrue(style.layerPropertyBatches.any { it.size > 1 }, "changed properties are batched")
    assertEquals(JsonPrimitive(0.75), style.layerProperty("a", "background-opacity"))
    assertEquals(JsonPrimitive("green"), style.layerProperty("a", "background-color"))
    assertEquals(JsonPrimitive("yellow"), style.layerProperty("b", "background-color"))

    val batchCount = style.layerPropertyBatches.size
    reconciler.apply(
      style,
      revision(background("a", "green", opacity = 0.75), background("b", "yellow")),
    )
    assertEquals(
      batchCount,
      style.layerPropertyBatches.size,
      "an unchanged revision writes nothing",
    )
  }

  @Test
  fun a_transition_writes_before_the_value_it_times() {
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
  fun a_rejected_transition_does_not_stop_later_value_writes_in_the_same_batch() = runTest {
    val style =
      RecordingStyleBinding(refusedLayerProperties = setOf("a:background-color-transition"))
    val reconciler = StyleReconciler()
    val originalTiming = """{"duration":100.0,"delay":0.0}"""
    reconciler.apply(style, revision(background("a", "red", transition = originalTiming)))
    val changed =
      revision(background("a", "green", transition = """{"duration":300.0,"delay":50.0}"""))
    reconciler.apply(style, changed)

    // Transitions precede their values, so the accepted color is after the rejected write.
    assertEquals(
      Json.parseToJsonElement(originalTiming),
      style.layerProperty("a", "background-color-transition"),
    )
    assertEquals(JsonPrimitive("green"), style.layerProperty("a", "background-color"))
    assertEquals(1, style.layerPropertyBatches.size)

    reconciler.apply(style, changed)
    assertEquals(1, style.layerPropertyBatches.size, "an unchanged rejected value is not retried")
  }

  private fun revision(vararg layers: LayerDefinition): StyleSnapshot =
    StyleSnapshot(
      sources = emptyList(),
      layers = layers.map { StyleSnapshot.Layer(it, Anchor.Top, null, null) },
      images = emptyList(),
    )

  private fun background(
    id: String,
    color: String,
    transition: String? = null,
    opacity: Double = 1.0,
  ): LayerDefinition =
    LayerDefinition(
      value =
        buildJsonObject {
          put("id", JsonPrimitive(id))
          put("type", JsonPrimitive("background"))
          put(
            "paint",
            buildJsonObject {
              put("background-color", JsonPrimitive(color))
              put("background-opacity", JsonPrimitive(opacity))
              if (transition != null) {
                put("background-color-transition", Json.parseToJsonElement(transition))
              }
            },
          )
        }
    )
}
