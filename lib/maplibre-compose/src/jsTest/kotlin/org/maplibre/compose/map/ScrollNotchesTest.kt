package org.maplibre.compose.map

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollNotchesTest {

  @Test
  fun one_wheel_detent_is_one_notch() {
    // Chromium and WebKit step a wheel by 100 CSS pixels.
    assertEquals(1.0, scrollNotches(100f, Density(1f)), 1e-9)
  }

  @Test
  fun the_same_gesture_is_the_same_zoom_on_a_hidpi_display() {
    // Compose has already divided the 100 CSS pixels by the density, so the raw delta halves.
    assertEquals(
      scrollNotches(100f, Density(1f)),
      scrollNotches(50f, Density(2f)),
      1e-9,
      "a CSS pixel is the same size whatever the display, so the zoom must be too",
    )
    assertEquals(scrollNotches(100f, Density(1f)), scrollNotches(40f, Density(2.5f)), 1e-9)
  }

  @Test
  fun a_trackpad_reports_a_fraction_of_a_notch() {
    assertEquals(0.12, scrollNotches(12f, Density(1f)), 1e-9)
  }

  @Test
  fun the_direction_is_kept() {
    assertEquals(-1.0, scrollNotches(-100f, Density(1f)), 1e-9)
  }
}
