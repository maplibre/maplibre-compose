package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GestureMathTest {
  @Test
  fun shove_accepts_staggered_fingers_moving_up_or_down() {
    for (direction in listOf(-1f, 1f)) {
      assertTrue(
        GestureMath.shouldStartShove(
          Offset(4f, 20f) * direction,
          Offset(2f, 24f) * direction,
          45.0,
        ),
      )
    }
    assertFalse(GestureMath.shouldStartShove(Offset(0f, 24f), Offset(0f, 24f), 46.0))
    assertFalse(GestureMath.shouldStartShove(Offset(0f, 15f), Offset(0f, 15f), 30.0))
  }

  @Test
  fun shove_requires_both_fingers_to_move_predominantly_vertically_in_the_same_direction() {
    val vertical = Offset(0f, 40f)
    for (other in listOf(Offset.Zero, Offset(0f, -4f), Offset(24f, 20f), Offset(20f, 20f))) {
      assertFalse(GestureMath.shouldStartShove(vertical, other, 0.0), "accepted $other")
      assertFalse(GestureMath.shouldStartShove(other, vertical, 0.0), "accepted $other")
    }
  }

  @Test
  fun pinch_preserves_direction_and_reverses_with_the_finger_motion() {
    assertEquals(1.0, GestureMath.pinchScale(1.0))
    assertTrue(GestureMath.pinchScale(1.5) > 1.0)
    assertTrue(GestureMath.pinchScale(2.0) > GestureMath.pinchScale(1.5))
    assertEquals(1.0, GestureMath.pinchScale(1.5) * GestureMath.pinchScale(1.0 / 1.5), 1e-12)
    for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
      assertEquals(1.0, GestureMath.pinchScale(invalid))
    }
  }

  @Test
  fun quick_zoom_follows_direction_and_the_configured_range() {
    assertEquals(2.0, GestureMath.quickZoomDelta(500.0, 1000.0, 4.0))
    assertEquals(-2.0, GestureMath.quickZoomDelta(-500.0, 1000.0, 4.0))
    assertEquals(1.0, GestureMath.quickZoomDelta(500.0, 1000.0, 2.0))
    assertEquals(0.0, GestureMath.quickZoomDelta(500.0, 0.0, 4.0))
  }

  @Test
  fun a_long_screen_offset_splits_into_bounded_steps_that_sum() {
    val steps = mutableListOf<Pair<Double, Double>>()
    GestureMath.forEachScreenSpaceStep(0.0, 80.0, maxStepDp = 16.0) { x, y -> steps += x to y }
    assertTrue(steps.isNotEmpty())
    assertTrue(steps.all { abs(it.first) < 1e-12 && abs(it.second) <= 16.0 + 1e-12 })
    assertEquals(0.0, steps.sumOf { it.first }, 1e-12)
    assertEquals(80.0, steps.sumOf { it.second }, 1e-12)
  }

  @Test
  fun a_screen_offset_too_long_for_its_step_limit_takes_longer_steps() {
    val steps = mutableListOf<Double>()
    GestureMath.forEachScreenSpaceStep(3200.0, 0.0, maxStepDp = 16.0, maxSteps = 100) { x, _ ->
      steps += x
    }
    assertEquals(List(100) { 32.0 }, steps)
  }
}
