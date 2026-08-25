package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TapPairingTest {
  @Test
  fun a_too_soon_down_is_a_bounce_only_inside_slop() {
    assertTrue(bounce(elapsedMillis = 0, distancePx = 0f))
    assertTrue(bounce(elapsedMillis = 39, distancePx = 100f))
    assertFalse(bounce(elapsedMillis = 0, distancePx = 100.01f))
    assertFalse(bounce(elapsedMillis = 40, distancePx = 0f))
  }

  @Test
  fun compose_pairs_a_second_down_inside_the_timeout_and_after_the_min_time() {
    assertFalse(paired(elapsedMillis = 39))
    assertTrue(paired(elapsedMillis = 40))
    assertTrue(paired(elapsedMillis = 300))
    assertFalse(paired(elapsedMillis = 301))
  }

  @Test
  fun a_second_down_must_match_pointer_type_and_stay_inside_slop() {
    assertFalse(paired(samePointerType = false))
    assertFalse(paired(distancePx = 100.01f))
    assertTrue(paired(distancePx = 100f))
  }

  @Test
  fun touch_uses_android_double_tap_slop_rather_than_scale_slop() {
    assertEquals(100.0, GestureMath.DOUBLE_TAP_SLOP_DP)
    val androidSlop = GestureMath.DOUBLE_TAP_SLOP_DP.toFloat()
    val scaleSlop = GestureMath.SCALE_START_SPAN_DP.toFloat()
    assertTrue(paired(distancePx = 50f, slopPx = androidSlop))
    assertFalse(paired(distancePx = 50f, slopPx = scaleSlop))
  }

  private fun bounce(
    elapsedMillis: Long = 0,
    distancePx: Float = 0f,
    samePointerType: Boolean = true,
    minTimeMillis: Long = 40,
    slopPx: Float = 100f,
  ): Boolean =
    isBounceSecondTap(
      elapsedMillis = elapsedMillis,
      distancePx = distancePx,
      samePointerType = samePointerType,
      minTimeMillis = minTimeMillis,
      slopPx = slopPx,
    )

  private fun paired(
    elapsedMillis: Long = 80,
    distancePx: Float = 0f,
    samePointerType: Boolean = true,
    minTimeMillis: Long = 40,
    timeoutMillis: Long = 300,
    slopPx: Float = 100f,
  ): Boolean =
    isPairedSecondTap(
      elapsedMillis = elapsedMillis,
      distancePx = distancePx,
      samePointerType = samePointerType,
      minTimeMillis = minTimeMillis,
      timeoutMillis = timeoutMillis,
      slopPx = slopPx,
    )
}
