package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.awt.Canvas
import java.awt.event.MouseWheelEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopScrollInputTest {
  @Test
  fun awt_scroll_amount_applies_to_both_axes_on_each_desktop_platform() {
    for (os in listOf("mac os x", "windows", "linux")) {
      val raw = Offset(0.5f, -2f)
      val density = Density(2f)
      val bounds = IntSize(800, 600)
      val one =
        desktopScrollDelta(raw, wheel(MouseWheelEvent.WHEEL_UNIT_SCROLL, 1), density, bounds, os)
      val three =
        desktopScrollDelta(raw, wheel(MouseWheelEvent.WHEEL_UNIT_SCROLL, 3), density, bounds, os)
      assertTrue(one.x < 0f && one.y > 0f, "unit scroll must produce displacement on both axes")
      assertEquals(one * 3f, three)
    }
  }

  @Test
  fun awt_page_scroll_uses_the_viewport_on_each_desktop_platform() {
    for (os in listOf("mac os x", "windows", "linux")) {
      val density = Density(2f)
      val delta =
        desktopScrollDelta(
          Offset(1f, -1f),
          wheel(MouseWheelEvent.WHEEL_BLOCK_SCROLL, 1),
          density,
          IntSize(800, 600),
          os,
        )
      assertEquals(DpOffset((-400).dp, 300.dp), normalizeScroll(delta, density))
    }
  }

  private fun wheel(type: Int, amount: Int) =
    MouseWheelEvent(Canvas(), MouseWheelEvent.MOUSE_WHEEL, 0L, 0, 0, 0, 0, false, type, amount, 1)
}
