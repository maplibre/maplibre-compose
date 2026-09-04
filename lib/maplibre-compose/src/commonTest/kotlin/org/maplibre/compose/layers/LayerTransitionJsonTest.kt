package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.animatorDurationScale
import org.maplibre.compose.style.install
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class LayerTransitionJsonTest {

  @Test
  fun a_transition_is_written_beside_its_property_in_milliseconds() {
    val layer = circleLayer()

    layer.setCircleColorTransition(TransitionOptions(1.5.seconds, 250.milliseconds))

    val transition =
      assertNotNull(paintOf(layer)["circle-color-transition"] as? JsonObject, "no transition key")
    // Compared as numbers because Kotlin/JS prints the double 1500.0 as 1500. The values are the
    // written timing under the platform's animator duration scale.
    val scale = animatorDurationScale().toDouble()
    assertEquals(
      mapOf("duration" to 1500.0 * scale, "delay" to 250.0 * scale),
      transition.mapValues { (_, value) -> value.jsonPrimitive.double },
    )
  }

  /**
   * The first composition runs every `set` block, including the ones holding null, so a null must
   * leave no key in the layer definition, whether or not a timing was set before it.
   */
  @Test
  fun a_layer_writes_no_transition_key_for_a_transition_nobody_set() {
    val layer = circleLayer()
    layer.setCircleColorTransition(TransitionOptions(1.5.seconds, 250.milliseconds))

    layer.setCircleRadiusTransition(null)
    layer.setCircleColorTransition(null)
    layer.setCircleBlurTransition(null)
    layer.setCircleOpacityTransition(null)
    layer.setCircleTranslateTransition(null)
    layer.setCircleStrokeWidthTransition(null)
    layer.setCircleStrokeColorTransition(null)
    layer.setCircleStrokeOpacityTransition(null)

    assertEquals(emptyList(), paintOf(layer).keys.filter { it.endsWith("-transition") })
  }

  /**
   * A paint key that disappears is pushed as null, which MapLibre Native rejects for a transition
   * while keeping the previous timing, so a transition is pushed as the spec's empty object
   * instead.
   */
  @Test
  fun a_cleared_transition_is_pushed_as_an_empty_object() {
    val style = RecordingStyleBinding()
    val installation =
      style.install(
        unknownCircleLayer(
          """{"circle-color":"#ff0000","circle-color-transition":{"duration":1500.0,"delay":250.0}}"""
        )
      )

    installation.update(unknownCircleLayer("""{"circle-blur":1.0}""").definition())

    val paint = assertNotNull(style.layers["circles"]?.get("paint") as? JsonObject)
    assertEquals(
      mapOf("circle-color" to JsonNull, "circle-color-transition" to JsonObject(emptyMap())),
      paint.filterKeys { it.startsWith("circle-color") },
    )
  }

  private fun circleLayer(): CircleLayer =
    CircleLayer(
      "circles",
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions()),
    )

  /** The paint object of [layer]. `toJson()` omits it when the layer has nothing set. */
  private fun paintOf(layer: Layer): Map<String, JsonElement> =
    (layer.definition().value["paint"] as? JsonObject).orEmpty()

  private fun unknownCircleLayer(paint: String): UnknownLayer =
    UnknownLayer(
      "circles",
      Json.parseToJsonElement("""{"id":"circles","type":"circle","paint":$paint}""") as JsonObject,
    )
}
