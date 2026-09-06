package org.maplibre.compose.interaction.internal

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
    val zoomIn = assertNotNull(GestureMath.scaleVelocity(4.0))
    val zoomOut = assertNotNull(GestureMath.scaleVelocity(-4.0))
    assertTrue(zoomIn.zoomDelta > 0.0)
    assertEquals(-zoomIn.zoomDelta, zoomOut.zoomDelta)
    assertEquals(zoomIn.duration, zoomOut.duration)
  }

  @Test
  fun rotation_continuation_follows_angular_velocity_direction() {
    val clockwise = assertNotNull(GestureMath.rotationVelocity(90.0))
    val counterclockwise = assertNotNull(GestureMath.rotationVelocity(-90.0))
    assertTrue(clockwise.bearingDelta > 0.0)
    assertEquals(-clockwise.bearingDelta, counterclockwise.bearingDelta)
    assertEquals(clockwise.duration, counterclockwise.duration)
  }

  @Test
  fun fling_tuning_preserves_screen_space_travel_and_zero_disables_it() {
    assertNull(GestureMath.fling(1400.0, 0.0, PanMomentum(minimumSpeed = 1500.0)))
    val fling =
      assertNotNull(
        GestureMath.fling(
          1050.0,
          0.0,
          PanMomentum(baseTime = 100.milliseconds, durationScale = 2.0),
        )
      )
    val unscaled =
      assertNotNull(GestureMath.fling(1050.0, 0.0, PanMomentum(baseTime = 100.milliseconds)))
    assertTrue(unscaled.offsetXDp > 0.0)
    assertEquals(unscaled.duration * 2, fling.duration)
    assertEquals(unscaled.offsetXDp * 2, fling.offsetXDp, 1e-10)
    assertNull(GestureMath.fling(1050.0, 0.0, PanMomentum(durationScale = 0.0)))
    assertNull(GestureMath.fling(0.0, 0.0, PanMomentum(minimumSpeed = 0.0)))
    assertNull(GestureMath.fling(Double.NaN, 0.0))
  }

  @Test
  fun zoom_and_rotation_continuation_durations_are_scaled_and_capped() {
    fun scale(continuation: VelocityMomentum) = GestureMath.scaleVelocity(4.0, continuation)
    fun rotate(continuation: VelocityMomentum) = GestureMath.rotationVelocity(90.0, continuation)
    for (calculate in
      listOf<(VelocityMomentum) -> Duration?>(
        { scale(it)?.duration },
        { rotate(it)?.duration },
      )) {
      assertEquals(
        120.milliseconds,
        calculate(VelocityMomentum(maximumDuration = 120.milliseconds)),
      )
      val full = assertNotNull(calculate(VelocityMomentum(maximumDuration = 1000.milliseconds)))
      val half =
        assertNotNull(
          calculate(VelocityMomentum(durationScale = 0.5, maximumDuration = 1000.milliseconds))
        )
      assertEquals(full / 2.0, half)
      assertNull(calculate(VelocityMomentum(durationScale = 0.0)))
      assertNull(calculate(VelocityMomentum(maximumDuration = Duration.ZERO)))
    }
  }

  @Test
  fun tilt_momentum_preserves_direction_and_obeys_configured_threshold_and_duration() {
    val settings = TiltMomentum(minimumSpeed = 8.0, duration = 200.milliseconds)
    assertNull(GestureMath.tiltVelocity(7.0, settings))
    val forward = assertNotNull(GestureMath.tiltVelocity(10.0, settings))
    val backward = assertNotNull(GestureMath.tiltVelocity(-10.0, settings))
    val longer =
      assertNotNull(GestureMath.tiltVelocity(10.0, settings.copy(duration = 400.milliseconds)))
    assertTrue(forward.pitchDelta > 0.0)
    assertEquals(-forward.pitchDelta, backward.pitchDelta, 1e-12)
    assertEquals(forward.duration, backward.duration)
    assertTrue(longer.pitchDelta > forward.pitchDelta)
    assertEquals(400.milliseconds, longer.duration)
    assertNull(GestureMath.tiltVelocity(10.0, settings.copy(duration = Duration.ZERO)))
    assertNull(GestureMath.tiltVelocity(0.0, settings.copy(minimumSpeed = 0.0)))
  }
}
