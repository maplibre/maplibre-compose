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

/** Pins the constants and equations inherited from MapLibre Android 13.5.0. */
class ClassicAndroidGestureMathTest {
  @Test
  fun pinch_uses_the_classic_logarithmic_zoom_coefficient() {
    val rawScale = 1.5
    val expectedZoomDelta = ln(rawScale) / ln(PI / 2.0) * 0.65

    assertEquals(2.0.pow(expectedZoomDelta), ClassicAndroidGestureMath.pinchScale(rawScale), 1e-12)
  }

  @Test
  fun quick_zoom_is_four_levels_per_viewport_and_follows_android_direction() {
    assertEquals(2.0, ClassicAndroidGestureMath.quickZoomDelta(500.0, 1000.0, 4.0))
    assertEquals(-2.0, ClassicAndroidGestureMath.quickZoomDelta(-500.0, 1000.0, 4.0))
  }

  @Test
  fun scale_requires_seven_dp_and_classic_speed_thresholds() {
    assertEquals(75.0, ClassicAndroidGestureMath.SCALE_START_WHILE_ROTATING_DP)
    assertFalse(ClassicAndroidGestureMath.shouldStartScale(6.99, 10.0, 10, 0.0))
    assertFalse(ClassicAndroidGestureMath.shouldStartScale(7.0, 5.9, 10, 0.0))
    assertFalse(ClassicAndroidGestureMath.shouldStartScale(7.0, 8.0, 10, 0.5))
    assertTrue(ClassicAndroidGestureMath.shouldStartScale(7.0, 9.0, 10, 0.5))
  }

  @Test
  fun rotation_uses_the_three_degree_and_velocity_dependent_thresholds() {
    assertFalse(ClassicAndroidGestureMath.shouldStartRotation(2.99, 1.0, 10))
    assertFalse(ClassicAndroidGestureMath.shouldStartRotation(4.0, 1.0, 10))
    assertTrue(ClassicAndroidGestureMath.shouldStartRotation(5.0, 0.5, 10))
    assertFalse(ClassicAndroidGestureMath.shouldStartRotation(6.0, 2.0, 10))
    assertTrue(ClassicAndroidGestureMath.shouldStartRotation(7.0, 2.0, 10))
  }

  @Test
  fun shove_requires_sixteen_dp_with_near_horizontal_fingers() {
    assertFalse(ClassicAndroidGestureMath.shouldStartShove(15.99, 0.0))
    assertTrue(ClassicAndroidGestureMath.shouldStartShove(16.0, 20.0))
    assertFalse(ClassicAndroidGestureMath.shouldStartShove(16.0, 20.01))
    assertFalse(ClassicAndroidGestureMath.hasStablePressure(0.67f, 1f))
    assertTrue(ClassicAndroidGestureMath.hasStablePressure(0.68f, 1f))
  }

  @Test
  fun fling_threshold_and_android_displacement_equation_are_preserved() {
    assertNull(ClassicAndroidGestureMath.fling(999.0, 0.0, pitch = 0.0))
    val fling = assertNotNull(ClassicAndroidGestureMath.fling(1400.0, 0.0, pitch = 0.0))
    val expectedDurationMillis = (1400.0 / 7.0 / 1.5 + 150.0).toLong()
    assertEquals(expectedDurationMillis, fling.duration.inWholeMilliseconds)
    assertEquals(1400.0 * expectedDurationMillis * 0.28 / 1000.0, fling.offsetXDp, 1e-12)
  }

  @Test
  fun opposite_vertical_flings_are_equal_and_opposite() {
    val down = assertNotNull(ClassicAndroidGestureMath.fling(0.0, 1400.0, pitch = 60.0))
    val up = assertNotNull(ClassicAndroidGestureMath.fling(0.0, -1400.0, pitch = 60.0))
    assertEquals(down.offsetYDp, -up.offsetYDp, 1e-12)
    assertEquals(down.duration, up.duration)
  }

  @Test
  fun android_tilt_term_shortens_a_pitched_fling() {
    val flat = assertNotNull(ClassicAndroidGestureMath.fling(0.0, 1400.0, pitch = 0.0))
    val pitched = assertNotNull(ClassicAndroidGestureMath.fling(0.0, 1400.0, pitch = 60.0))
    assertTrue(pitched.duration < flat.duration)
    assertTrue(abs(pitched.offsetYDp) < abs(flat.offsetYDp))
  }

  @Test
  fun screen_space_fling_matches_the_flat_android_equation() {
    val flat = assertNotNull(ClassicAndroidGestureMath.fling(0.0, 1400.0, pitch = 0.0))
    val screen = assertNotNull(ClassicAndroidGestureMath.screenSpaceFling(0.0, 1400.0))
    assertEquals(flat.offsetYDp, screen.offsetYDp, 1e-12)
    assertEquals(flat.duration, screen.duration)
  }

  @Test
  fun a_short_screen_offset_is_one_step() {
    val steps = mutableListOf<Pair<Double, Double>>()
    ClassicAndroidGestureMath.forEachScreenSpaceStep(3.0, 4.0, maxStepDp = 16.0) { x, y ->
      steps += x to y
    }
    assertEquals(listOf(3.0 to 4.0), steps)
  }

  @Test
  fun a_long_screen_offset_splits_into_bounded_steps_that_sum() {
    val steps = mutableListOf<Pair<Double, Double>>()
    ClassicAndroidGestureMath.forEachScreenSpaceStep(0.0, 80.0, maxStepDp = 16.0) { x, y ->
      steps += x to y
    }
    assertEquals(5, steps.size)
    assertTrue(steps.all { abs(it.first) < 1e-12 && abs(it.second) <= 16.0 + 1e-12 })
    assertEquals(0.0, steps.sumOf { it.first }, 1e-12)
    assertEquals(80.0, steps.sumOf { it.second }, 1e-12)
  }

  @Test
  fun pinch_velocity_continuation_keeps_classic_cap_and_sign() {
    val zoomIn =
      assertNotNull(
        ClassicAndroidGestureMath.scaleVelocity(
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
        ClassicAndroidGestureMath.scaleVelocity(
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
  fun rotation_velocity_uses_android_focal_projection_multiplier_and_sign() {
    assertNull(
      ClassicAndroidGestureMath.rotationVelocity(
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
        ClassicAndroidGestureMath.rotationVelocity(
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
      ClassicAndroidGestureMath.rotationVelocity(
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
}
