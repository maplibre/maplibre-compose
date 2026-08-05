package org.maplibre.compose.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMapExtentTest {

  @Test
  fun `derives physical size from logical size at integer scale`() {
    val extent = DesktopMapExtent.fromLogical(800, 600, 2.0)

    assertEquals(800, extent.width)
    assertEquals(600, extent.height)
    assertEquals(1600, extent.physicalWidth)
    assertEquals(1200, extent.physicalHeight)
  }

  @Test
  fun `derives logical size from physical size at integer scale`() {
    val extent = DesktopMapExtent.fromPhysical(1600, 1200, 2.0)

    assertEquals(800, extent.width)
    assertEquals(600, extent.height)
    assertEquals(1600, extent.physicalWidth)
    assertEquals(1200, extent.physicalHeight)
  }

  @Test
  fun `keeps logical and physical size self-consistent at fractional scale`() {
    // The round trip is not exact at fractional scale; the invariant is that the physical size the
    // host allocates always equals ceil(logical * scale).
    for (scale in listOf(1.25, 1.5, 1.75, 2.25, 2.5)) {
      for (physical in listOf(801, 1000, 1023, 1919, 2561)) {
        val extent = DesktopMapExtent.fromPhysical(physical, physical, scale)
        val expected = kotlin.math.ceil(extent.width * scale).toInt()

        assertEquals(expected, extent.physicalWidth, "physical width for $physical at scale $scale")
        assertTrue(
          extent.width > 0 && extent.physicalWidth > 0,
          "extent must stay renderable for $physical at scale $scale",
        )
      }
    }
  }

  @Test
  fun `treats a zero size as empty rather than as an error`() {
    // Compose reports a zero size before first layout routinely, and MapLibre rejects zero
    // dimensions natively, so this has to be representable rather than throwing.
    assertTrue(DesktopMapExtent.fromLogical(0, 100, 1.0).isEmpty)
    assertTrue(DesktopMapExtent.fromLogical(100, 0, 1.0).isEmpty)
    assertTrue(DesktopMapExtent.fromPhysical(0, 0, 2.0).isEmpty)
    assertTrue(DesktopMapExtent.Empty.isEmpty)
    assertFalse(DesktopMapExtent.fromLogical(1, 1, 1.0).isEmpty)
  }

  @Test
  fun `falls back to a scale of one when the scale factor is not usable`() {
    for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
      val extent = DesktopMapExtent.fromLogical(100, 100, bad)
      assertEquals(1.0, extent.scaleFactor, "scale $bad should fall back to 1.0")
      assertFalse(extent.isEmpty)
    }
  }

  @Test
  fun `compares by value so a resize to the same size is not a change`() {
    assertEquals(
      DesktopMapExtent.fromLogical(800, 600, 2.0),
      DesktopMapExtent.fromLogical(800, 600, 2.0),
    )
    assertEquals(
      DesktopMapExtent.fromLogical(800, 600, 2.0).hashCode(),
      DesktopMapExtent.fromLogical(800, 600, 2.0).hashCode(),
    )
    // A density change alone must compare unequal: it forces the map to be recreated, because
    // MapLibre fixes pixelRatio at creation.
    assertTrue(
      DesktopMapExtent.fromLogical(800, 600, 1.0) != DesktopMapExtent.fromLogical(800, 600, 2.0)
    )
  }
}
