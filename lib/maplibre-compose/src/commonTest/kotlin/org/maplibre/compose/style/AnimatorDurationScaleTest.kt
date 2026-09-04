package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AnimatorDurationScaleTest {

  @Test
  fun a_scale_of_one_returns_the_same_duration() {
    val duration = 300.milliseconds
    assertEquals(duration, duration.scaledBy(1f))
  }

  @Test
  fun scaling_multiplies_the_duration() {
    assertEquals(150.milliseconds, 300.milliseconds.scaledBy(0.5f))
  }

  /** A zero scale is Android's "remove animations": the camera jumps instead of easing. */
  @Test
  fun a_scale_of_zero_zeroes_the_duration() {
    assertEquals(Duration.ZERO, 300.milliseconds.scaledBy(0f))
  }

  @Test
  fun a_zero_duration_stays_zero_under_any_scale() {
    assertEquals(Duration.ZERO, Duration.ZERO.scaledBy(10f))
  }

  /**
   * Only the timing fields of a transition object scale. A cleared transition stays empty, and a
   * non-transition property is untouched.
   */
  @Test
  fun scaling_a_style_object_scales_only_its_transition_timings() {
    val paint =
      Json.parseToJsonElement(
          """{"fill-color":"red","fill-color-transition":{"duration":300.0,"delay":50.0},""" +
            """"fill-opacity-transition":{},"fill-opacity":0.5}"""
        )
        .jsonObject
    val scaled = paint.withScaledTransitions(0.5f)

    assertEquals(paint.keys, scaled.keys)
    assertEquals(paint["fill-color"], scaled["fill-color"])
    assertEquals(paint["fill-opacity"], scaled["fill-opacity"])
    assertEquals(paint["fill-opacity-transition"], scaled["fill-opacity-transition"])
    // Compared as numbers because Kotlin/JS prints the double 150.0 as 150.
    assertEquals(
      mapOf("duration" to 150.0, "delay" to 25.0),
      scaled.getValue("fill-color-transition").jsonObject.mapValues { (_, value) ->
        value.jsonPrimitive.double
      },
    )
  }
}
