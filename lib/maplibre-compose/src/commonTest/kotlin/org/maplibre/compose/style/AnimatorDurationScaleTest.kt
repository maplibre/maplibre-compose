package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
}
