package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollInputTest {
  @Test
  fun each_host_converts_each_axis_in_its_reported_units() {
    data class Case(val units: ScrollUnits, val x: Double, val y: Double, val notch: Double)
    val cases =
      listOf(
        Case(ScrollUnits.BrowserPixel, -3.0, 6.0, -0.06),
        Case(ScrollUnits.BrowserLine, -100.0, 200.0, -2.0),
        Case(ScrollUnits.BrowserPage, -900.0, 1200.0, -6.0),
        Case(ScrollUnits.MacRotation, -15.0, 30.0, -6.0),
        Case(ScrollUnits.Rotation, -120.0, 240.0, -6.0),
        Case(ScrollUnits.IosIndirect, -150.0, 300.0, -3.0),
      )
    for (case in cases) {
      val result =
        checkNotNull(normalizeScroll(Offset(3f, -6f), case.units, Density(2f), IntSize(600, 400)))
      assertEquals(case.x, result.panDelta.x.value.toDouble(), 0.00001, case.units.name)
      assertEquals(case.y, result.panDelta.y.value.toDouble(), 0.00001, case.units.name)
      assertEquals(case.notch, result.zoomComponent, 0.00001, case.units.name)
    }
  }

  @Test
  fun browser_pixel_and_line_units_are_independent_of_display_density() {
    for (units in listOf(ScrollUnits.BrowserPixel, ScrollUnits.BrowserLine)) {
      val normal = normalizeScroll(Offset(0f, 100f), units, Density(1f), IntSize(600, 400))
      val highDensity = normalizeScroll(Offset(0f, 100f), units, Density(2.5f), IntSize(600, 400))
      assertEquals(normal, highDensity)
    }
  }

  @Test
  fun dominant_axis_drives_zoom_and_y_wins_ties() {
    assertEquals(2.0, normalize(Offset(2f, 0f)).zoomComponent)
    assertEquals(3.0, normalize(Offset(3f, -2f)).zoomComponent)
    assertEquals(-3.0, normalize(Offset(3f, -3f)).zoomComponent)
    assertEquals(-4.0, normalize(Offset(3f, -4f)).zoomComponent)
  }

  @Test
  fun browser_pixel_classification_uses_raw_chromium_increments() {
    for (value in listOf(100f, -200f, 4.000244140625f, -12.000732421875f)) {
      assertEquals(ScrollKind.Discrete, normalize(Offset(0f, value), ScrollUnits.BrowserPixel).kind)
    }
    for (value in listOf(1f, 12f, 99f, 0.000001f)) {
      assertEquals(
        ScrollKind.Continuous,
        normalize(Offset(0f, value), ScrollUnits.BrowserPixel).kind,
      )
    }
    assertEquals(
      ScrollKind.Continuous,
      normalize(Offset(100f, 100f), ScrollUnits.BrowserPixel).kind,
    )
  }

  @Test
  fun line_and_page_events_are_discrete_even_with_two_axes() {
    for (units in listOf(ScrollUnits.BrowserLine, ScrollUnits.BrowserPage)) {
      assertEquals(ScrollKind.Discrete, normalize(Offset(0.5f, 0.5f), units).kind)
    }
  }

  @Test
  fun native_rotation_units_use_fractional_and_two_axis_heuristics() {
    for (units in listOf(ScrollUnits.Rotation, ScrollUnits.MacRotation)) {
      assertEquals(ScrollKind.Discrete, normalize(Offset(0f, -2f), units).kind)
      assertEquals(ScrollKind.Discrete, normalize(Offset(0f, 2.0005f), units).kind)
      assertEquals(ScrollKind.Continuous, normalize(Offset(0f, 2.002f), units).kind)
      assertEquals(ScrollKind.Continuous, normalize(Offset(0.5f, 0f), units).kind)
      assertEquals(ScrollKind.Continuous, normalize(Offset(1f, 1f), units).kind)
    }
    assertEquals(ScrollKind.Continuous, normalize(Offset(0f, 1f), ScrollUnits.IosIndirect).kind)
  }

  @Test
  fun zero_nonfinite_and_overflowing_samples_cannot_claim_input() {
    for (raw in
      listOf(
        Offset.Zero,
        Offset(-0f, 0f),
        Offset(Float.NaN, 1f),
        Offset(1f, Float.POSITIVE_INFINITY),
        Offset(Float.MAX_VALUE, 1f),
      )) {
      assertNull(normalizeScroll(raw, ScrollUnits.Rotation, Density(1f), IntSize(600, 400)))
    }
  }

  private fun normalize(raw: Offset, units: ScrollUnits = ScrollUnits.Rotation): NormalizedScroll =
    checkNotNull(normalizeScroll(raw, units, Density(1f), IntSize(600, 400)))
}
