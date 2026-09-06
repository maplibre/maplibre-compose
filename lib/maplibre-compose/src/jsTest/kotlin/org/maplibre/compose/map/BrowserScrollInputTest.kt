package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.w3c.dom.events.WheelEvent

class BrowserScrollInputTest {
  @Test
  fun equivalent_pixel_line_and_page_distances_pan_equally_at_each_density() {
    for (scale in listOf(1f, 2f)) {
      val density = Density(scale)
      val bounds = IntSize((400 * scale).toInt(), (200 * scale).toInt())
      val expected = DpOffset((-400).dp, 200.dp)
      for ((raw, mode) in
        listOf(
          Offset(400f, -200f) to WheelEvent.DOM_DELTA_PIXEL,
          Offset(20f, -10f) to WheelEvent.DOM_DELTA_LINE,
          Offset(1f, -1f) to WheelEvent.DOM_DELTA_PAGE,
        )) {
        assertEquals(
          expected,
          normalizeScroll(
            browserScrollDelta(raw, mode, density, bounds, lineHeight = 20f),
            density,
          ),
        )
      }
    }
  }

  @Test
  fun events_without_dom_metadata_use_css_pixels() {
    val density = Density(2f)
    assertEquals(
      DpOffset((-8).dp, 12.dp),
      normalizeScroll(
        browserScrollDelta(Offset(8f, -12f), null, density, IntSize(600, 400), lineHeight = 20f),
        density,
      ),
    )
  }
}
