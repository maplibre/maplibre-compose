package org.maplibre.compose.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Pins the thresholds and camera equations that [mapInput] uses. */
class GestureMathTest {
  @Test
  fun pinch_uses_the_logarithmic_zoom_coefficient() {
    val rawScale = 1.5
    val expectedZoomDelta = ln(rawScale) / ln(PI / 2.0) * 0.65

    assertEquals(2.0.pow(expectedZoomDelta), GestureMath.pinchScale(rawScale), 1e-12)
  }

  @Test
  fun quick_zoom_is_four_levels_per_viewport_and_follows_vertical_direction() {
    assertEquals(2.0, GestureMath.quickZoomDelta(500.0, 1000.0, 4.0))
    assertEquals(-2.0, GestureMath.quickZoomDelta(-500.0, 1000.0, 4.0))
  }

  @Test
  fun scale_requires_seven_dp_and_speed_thresholds() {
    assertEquals(75.0, GestureMath.SCALE_START_WHILE_ROTATING_DP)
    assertFalse(GestureMath.shouldStartScale(6.99, 10.0, 10, 0.0))
    assertFalse(GestureMath.shouldStartScale(7.0, 5.9, 10, 0.0))
    assertFalse(GestureMath.shouldStartScale(7.0, 8.0, 10, 0.5))
    assertTrue(GestureMath.shouldStartScale(7.0, 9.0, 10, 0.5))
  }

  @Test
  fun rotation_uses_the_three_degree_and_velocity_dependent_thresholds() {
    assertFalse(GestureMath.shouldStartRotation(2.99, 1.0, 10))
    assertFalse(GestureMath.shouldStartRotation(4.0, 1.0, 10))
    assertTrue(GestureMath.shouldStartRotation(5.0, 0.5, 10))
    assertFalse(GestureMath.shouldStartRotation(6.0, 2.0, 10))
    assertTrue(GestureMath.shouldStartRotation(7.0, 2.0, 10))
  }

  @Test
  fun shove_requires_sixteen_dp_with_near_horizontal_fingers() {
    assertFalse(GestureMath.shouldStartShove(15.99, 0.0))
    assertTrue(GestureMath.shouldStartShove(16.0, 20.0))
    assertFalse(GestureMath.shouldStartShove(16.0, 20.01))
    assertFalse(GestureMath.hasStablePressure(0.67f, 1f))
    assertTrue(GestureMath.hasStablePressure(0.68f, 1f))
  }

  @Test
  fun fling_threshold_and_displacement_equation() {
    assertNull(GestureMath.fling(999.0, 0.0))
    val fling = assertNotNull(GestureMath.fling(1400.0, 0.0))
    val expectedDurationMillis = (1400.0 / 7.0 / 1.5 + 150.0).toLong()
    assertEquals(expectedDurationMillis, fling.duration.inWholeMilliseconds)
    assertEquals(1400.0 * expectedDurationMillis * 0.28 / 1000.0, fling.offsetXDp, 1e-12)
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
    assertEquals(5, steps.size)
    assertTrue(steps.all { abs(it.first) < 1e-12 && abs(it.second) <= 16.0 + 1e-12 })
    assertEquals(0.0, steps.sumOf { it.first }, 1e-12)
    assertEquals(80.0, steps.sumOf { it.second }, 1e-12)
  }

  @Test
  fun pinch_velocity_continuation_keeps_the_cap_and_sign() {
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
    assertEquals(2.5, zoomIn.zoomDelta)

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
    assertEquals(-2.5, zoomOut.zoomDelta)
  }

  @Test
  fun rotation_velocity_uses_focal_projection_multiplier_and_sign() {
    assertNull(
      GestureMath.rotationVelocity(
        velocityXPixelsPerSecond = 0.0,
        velocityYPixelsPerSecond = 5.0,
        focalXPixel = 100.0,
        focalYPixel = 0.0,
        lastRotationDegrees = 1.0,
        density = 1.0,
      )
    )
    val clockwise =
      assertNotNull(
        GestureMath.rotationVelocity(
          velocityXPixelsPerSecond = 0.0,
          velocityYPixelsPerSecond = 1000.0,
          focalXPixel = 100.0,
          focalYPixel = 0.0,
          lastRotationDegrees = -1.0,
          density = 1.0,
        )
      )
    assertEquals(-13.0, clockwise.initialDegreesPerFrame, 1e-12)

    assertNull(
      GestureMath.rotationVelocity(
        velocityXPixelsPerSecond = 0.0,
        velocityYPixelsPerSecond = 1000.0,
        focalXPixel = 100.0,
        focalYPixel = 0.0,
        lastRotationDegrees = 0.01,
        density = 1.0,
        scaling = true,
      )
    )
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
    assertNull(GestureMath.fling(1400.0, 0.0, Fling(minimumSpeed = 1500.0)))
    val fling =
      assertNotNull(
        GestureMath.fling(1050.0, 0.0, Fling(baseTime = 100.milliseconds, durationScale = 2.0))
      )
    assertEquals(400.milliseconds, fling.duration)
    assertEquals(117.6, fling.offsetXDp, 1e-10)
    assertNull(GestureMath.fling(1050.0, 0.0, Fling(durationScale = 0.0)))
    assertNull(GestureMath.fling(0.0, 0.0, Fling(minimumSpeed = 0.0)))
    assertNull(GestureMath.fling(Double.NaN, 0.0))
  }

  @Test
  fun zoom_and_rotation_continuation_durations_are_scaled_and_capped() {
    fun scale(continuation: GestureVelocityContinuation) =
      GestureMath.scaleVelocity(6000.0, 6000.0, 20.0, 1.0, false, continuation)
    fun rotate(continuation: GestureVelocityContinuation) =
      GestureMath.rotationVelocity(0.0, 1000.0, 100.0, 0.0, -1.0, 1.0, continuation = continuation)
    for (calculate in
      listOf<(GestureVelocityContinuation) -> Duration?>(
        { scale(it)?.duration },
        { rotate(it)?.duration },
      )) {
      assertEquals(300.milliseconds, calculate(GestureVelocityContinuation()))
      assertEquals(
        120.milliseconds,
        calculate(GestureVelocityContinuation(maximumDuration = 120.milliseconds)),
      )
      val full =
        assertNotNull(calculate(GestureVelocityContinuation(maximumDuration = 1000.milliseconds)))
      val half =
        assertNotNull(
          calculate(
            GestureVelocityContinuation(durationScale = 0.5, maximumDuration = 1000.milliseconds)
          )
        )
      assertEquals(full / 2.0, half)
      assertNull(calculate(GestureVelocityContinuation(durationScale = 0.0)))
      assertNull(calculate(GestureVelocityContinuation(maximumDuration = Duration.ZERO)))
    }
  }

  @Test
  fun tilt_integrates_linear_velocity_decay_with_signed_direction_and_threshold() {
    assertNull(GestureMath.tiltVelocity(4.99))
    assertEquals(0.75, assertNotNull(GestureMath.tiltVelocity(10.0)).pitchDelta, 1e-12)
    assertEquals(-0.75, assertNotNull(GestureMath.tiltVelocity(-10.0)).pitchDelta, 1e-12)
    assertNull(GestureMath.tiltVelocity(10.0, TiltContinuation(duration = Duration.ZERO)))
    assertNull(GestureMath.tiltVelocity(0.0, TiltContinuation(minimumSpeed = 0.0)))
  }
}
