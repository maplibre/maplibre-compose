package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureMathTest {
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
  fun faster_flings_travel_further_and_last_longer() {
    assertNull(GestureMath.fling(0.0, 0.0))
    val slow = assertNotNull(GestureMath.fling(1400.0, 0.0))
    val fast = assertNotNull(GestureMath.fling(2800.0, 0.0))
    assertTrue(slow.offsetXDp > 0.0)
    assertEquals(0.0, slow.offsetYDp)
    assertTrue(fast.offsetXDp > slow.offsetXDp)
    assertTrue(fast.duration > slow.duration)
  }

  @Test
  fun opposite_vertical_flings_are_equal_and_opposite() {
    val down = assertNotNull(GestureMath.fling(0.0, 1400.0))
    val up = assertNotNull(GestureMath.fling(0.0, -1400.0))
    assertEquals(down.offsetYDp, -up.offsetYDp, 1e-12)
    assertEquals(down.duration, up.duration)
  }

  @Test
  fun a_short_screen_offset_is_one_step() {
    val steps = mutableListOf<Pair<Double, Double>>()
    GestureMath.forEachScreenSpaceStep(3.0, 4.0, maxStepDp = 16.0) { x, y -> steps += x to y }
    assertEquals(listOf(3.0 to 4.0), steps)
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
  fun pinch_velocity_continuation_follows_zoom_direction() {
    val zoomIn =
      assertNotNull(
        GestureMath.scaleVelocity(
          velocityXPixelsPerSecond = 6000.0,
          velocityYPixelsPerSecond = 6000.0,
          spanSinceLastPixels = 20.0,
          density = 1.0,
          scalingOut = false,
        )
      )
    assertTrue(zoomIn.zoomDelta > 0.0)

    val zoomOut =
      assertNotNull(
        GestureMath.scaleVelocity(
          velocityXPixelsPerSecond = 6000.0,
          velocityYPixelsPerSecond = 6000.0,
          spanSinceLastPixels = 20.0,
          density = 1.0,
          scalingOut = true,
        )
      )
    assertEquals(-zoomIn.zoomDelta, zoomOut.zoomDelta)
    assertEquals(zoomIn.duration, zoomOut.duration)
  }

  @Test
  fun rotation_continuation_follows_the_last_rotation_direction() {
    fun rotation(lastRotation: Double) =
      assertNotNull(
        GestureMath.rotationVelocity(
          velocityXPixelsPerSecond = 0.0,
          velocityYPixelsPerSecond = 1000.0,
          focalXPixel = 100.0,
          focalYPixel = 0.0,
          lastRotationDegrees = lastRotation,
          density = 1.0,
        )
      )
    val clockwise = rotation(1.0)
    val counterclockwise = rotation(-1.0)
    assertTrue(clockwise.initialDegreesPerFrame > 0.0)
    assertEquals(-clockwise.initialDegreesPerFrame, counterclockwise.initialDegreesPerFrame)
    assertEquals(clockwise.duration, counterclockwise.duration)
  }
}
