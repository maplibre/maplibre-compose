package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollInputTest {
  @Test
  fun displacement_is_reported_in_dp_at_each_display_density() {
    for (scale in listOf(1f, 2f, 2.5f)) {
      assertEquals(
        DpOffset(12.dp, (-24).dp),
        normalizeScroll(Offset(12f, -24f) * scale, Density(scale)),
      )
    }
  }

  @Test
  fun unusable_displacement_cannot_claim_input() {
    for (delta in listOf(Offset.Zero, Offset(Float.NaN, 1f), Offset(1f, Float.POSITIVE_INFINITY))) {
      assertNull(normalizeScroll(delta, Density(1f)))
    }
    for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
      assertNull(normalizeScroll(Offset(1f, 1f), Density(scale)))
    }
    assertNull(normalizeScroll(Offset(Float.MAX_VALUE, 1f), Density(0.5f)))
  }
}
