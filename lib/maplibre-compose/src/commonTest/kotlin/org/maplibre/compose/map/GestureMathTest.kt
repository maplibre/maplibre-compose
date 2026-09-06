package org.maplibre.compose.map

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

  @Test
  fun equal_time_samples_use_spatial_slop_without_artificial_speed_rejection() {
    assertFalse(GestureMath.shouldStartScale(6.9, 6.9, 0, 0.0))
    assertTrue(GestureMath.shouldStartScale(7.0, 7.0, 0, 2.0))
    assertFalse(GestureMath.shouldStartRotation(2.9, 2.9, 0))
    assertTrue(GestureMath.shouldStartRotation(3.0, 3.0, 0))
    assertFalse(GestureMath.shouldStartRotation(3.0, 3.0, 1))
    assertFalse(GestureMath.shouldStartScale(100.0, 100.0, -1, 0.0))
    assertFalse(GestureMath.shouldStartRotation(100.0, 100.0, -1))
  }

  @Test
  fun selected_thresholds_replace_the_family_defaults() {
    assertTrue(GestureMath.shouldStartScale(5.0, 5.0, 0, 0.0, startSpanSlopDp = 5.0))
    assertFalse(GestureMath.shouldStartScale(8.0, 8.0, 0, 0.0, startSpanSlopDp = 9.0))
    assertTrue(GestureMath.shouldStartRotation(2.0, 2.0, 0, startAngleDegrees = 2.0))
    assertFalse(GestureMath.shouldStartRotation(9.0, 9.0, 0, startAngleDegrees = 10.0))
    assertTrue(GestureMath.shouldStartShove(8.0, 15.0, startSlopDp = 8.0))
    assertFalse(GestureMath.shouldStartShove(8.0, 21.0, startSlopDp = 8.0))
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
    assertEquals(400.milliseconds, fling.duration)
    assertEquals(117.6, fling.offsetXDp, 1e-10)
    assertNull(GestureMath.fling(1050.0, 0.0, PanMomentum(durationScale = 0.0)))
    assertNull(GestureMath.fling(0.0, 0.0, PanMomentum(minimumSpeed = 0.0)))
    assertNull(GestureMath.fling(Double.NaN, 0.0))
  }

  @Test
  fun zoom_and_rotation_continuation_durations_are_scaled_and_capped() {
    fun scale(continuation: VelocityMomentum) =
      GestureMath.scaleVelocity(6000.0, 6000.0, 20.0, 1.0, false, continuation)
    fun rotate(continuation: VelocityMomentum) =
      GestureMath.rotationVelocity(0.0, 1000.0, 100.0, 0.0, -1.0, 1.0, continuation = continuation)
    for (calculate in
      listOf<(VelocityMomentum) -> Duration?>(
        { scale(it)?.duration },
        { rotate(it)?.duration },
      )) {
      assertEquals(300.milliseconds, calculate(VelocityMomentum()))
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
  fun tilt_integrates_linear_velocity_decay_with_signed_direction_and_threshold() {
    assertNull(GestureMath.tiltVelocity(4.99))
    assertEquals(0.75, assertNotNull(GestureMath.tiltVelocity(10.0)).pitchDelta, 1e-12)
    assertEquals(-0.75, assertNotNull(GestureMath.tiltVelocity(-10.0)).pitchDelta, 1e-12)
    assertNull(GestureMath.tiltVelocity(10.0, TiltMomentum(duration = Duration.ZERO)))
    assertNull(GestureMath.tiltVelocity(0.0, TiltMomentum(minimumSpeed = 0.0)))
  }
}
