package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapExtentTest {

  @Test
  fun derives_physical_size_from_logical_size_at_integer_scale() {
    val extent = MapExtent.fromLogical(800, 600, 2.0)

    assertEquals(800, extent.width)
    assertEquals(600, extent.height)
    assertEquals(1600, extent.physicalWidth)
    assertEquals(1200, extent.physicalHeight)
  }

  @Test
  fun derives_logical_size_from_physical_size_at_integer_scale() {
    val extent = MapExtent.fromPhysical(1600, 1200, 2.0)

    assertEquals(800, extent.width)
    assertEquals(600, extent.height)
    assertEquals(1600, extent.physicalWidth)
    assertEquals(1200, extent.physicalHeight)
  }

  @Test
  fun preserves_the_host_physical_size_at_fractional_scale() {
    // 1.7f reproduces GLFW's scale precisely: widening it to Double produces 1.700000047..., so
    // recalculating ceil(960 * scale) would incorrectly turn a 1632-pixel framebuffer into 1633.
    for (scale in listOf(1.25, 1.5, 1.7f.toDouble(), 1.75, 2.25, 2.5)) {
      for (physical in listOf(801, 1000, 1023, 1088, 1632, 1919, 2561)) {
        val extent = MapExtent.fromPhysical(physical, physical, scale)
        val expectedLogical = kotlin.math.ceil(physical / scale).toInt()

        assertEquals(expectedLogical, extent.width, "logical width for $physical at scale $scale")
        assertEquals(physical, extent.physicalWidth, "physical width at scale $scale")
        assertTrue(
          extent.width > 0 && extent.physicalWidth > 0,
          "extent must stay renderable for $physical at scale $scale",
        )
      }
    }
  }

  @Test
  fun treats_a_zero_size_as_empty_rather_than_as_an_error() {
    // Compose reports a zero size before first layout routinely, and MapLibre rejects zero
    // dimensions natively, so this has to be representable rather than throwing.
    assertTrue(MapExtent.fromLogical(0, 100, 1.0).isEmpty)
    assertTrue(MapExtent.fromLogical(100, 0, 1.0).isEmpty)
    assertTrue(MapExtent.fromPhysical(0, 0, 2.0).isEmpty)
    assertTrue(MapExtent.Empty.isEmpty)
    assertFalse(MapExtent.fromLogical(1, 1, 1.0).isEmpty)
  }

  @Test
  fun falls_back_to_a_scale_of_one_when_the_scale_factor_is_not_usable() {
    for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
      val extent = MapExtent.fromLogical(100, 100, bad)
      assertEquals(1.0, extent.scaleFactor, "scale $bad should fall back to 1.0")
      assertFalse(extent.isEmpty)
    }
  }

  @Test
  fun compares_by_value_so_a_resize_to_the_same_size_is_not_a_change() {
    assertEquals(MapExtent.fromLogical(800, 600, 2.0), MapExtent.fromLogical(800, 600, 2.0))
    assertEquals(
      MapExtent.fromLogical(800, 600, 2.0).hashCode(),
      MapExtent.fromLogical(800, 600, 2.0).hashCode(),
    )
    // A density change alone must compare unequal: it forces the map to be recreated, because
    // MapLibre fixes pixelRatio at creation.
    assertTrue(MapExtent.fromLogical(800, 600, 1.0) != MapExtent.fromLogical(800, 600, 2.0))
  }
}
