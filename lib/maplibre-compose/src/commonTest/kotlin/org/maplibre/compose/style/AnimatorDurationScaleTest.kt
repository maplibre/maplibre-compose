package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.camera.CameraAnimation

class AnimatorDurationScaleTest {

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

  /** A flight without a duration slows its speed instead, so the flight still takes longer. */
  @Test
  fun scaling_a_camera_animation_scales_its_timing() {
    assertEquals(
      CameraAnimation.Ease(600.milliseconds),
      CameraAnimation.Ease(300.milliseconds).scaledBy(2f),
    )
    assertEquals(CameraAnimation.Fly(2.seconds), CameraAnimation.Fly(1.seconds).scaledBy(2f))
    assertEquals(CameraAnimation.Fly(speed = 1.0), CameraAnimation.Fly(speed = 2.0).scaledBy(2f))
    assertEquals(
      CameraAnimation.Fly(speed = CameraAnimation.Fly.DefaultSpeed / 2),
      CameraAnimation.Fly().scaledBy(2f),
    )
  }

  /** A scale of zero turns every transition into a jump, including a flight paced by speed. */
  @Test
  fun a_zero_scale_makes_a_camera_animation_a_jump() {
    assertEquals(CameraAnimation.Ease(Duration.ZERO), CameraAnimation.Ease(1.seconds).scaledBy(0f))
    assertEquals(CameraAnimation.Fly(Duration.ZERO), CameraAnimation.Fly(1.seconds).scaledBy(0f))
    assertEquals(CameraAnimation.Fly(Duration.ZERO), CameraAnimation.Fly(speed = 2.0).scaledBy(0f))
  }
}
