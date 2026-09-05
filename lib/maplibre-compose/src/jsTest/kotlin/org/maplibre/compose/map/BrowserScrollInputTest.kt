package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.events.WheelEventInit

class BrowserScrollInputTest {
  @Test
  fun reads_the_retained_wheel_events_delta_mode() {
    assertEquals(
      ScrollUnits.BrowserPixel,
      browserScrollUnits(WheelEvent("wheel", WheelEventInit(deltaMode = 0))),
    )
    assertEquals(
      ScrollUnits.BrowserLine,
      browserScrollUnits(WheelEvent("wheel", WheelEventInit(deltaMode = 1))),
    )
    assertEquals(
      ScrollUnits.BrowserPage,
      browserScrollUnits(WheelEvent("wheel", WheelEventInit(deltaMode = 2))),
    )
  }

  @Test
  fun missing_native_metadata_uses_pixel_units() {
    assertEquals(ScrollUnits.BrowserPixel, browserScrollUnits(null))
    assertEquals(ScrollUnits.BrowserPixel, browserScrollUnits("synthetic"))
  }
}
