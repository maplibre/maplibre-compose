package org.maplibre.compose.demoapp.demos

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MagnifyingLensLayoutTest {

  @Test
  fun the_unobstructed_center_is_the_inner_region_center() {
    assertEquals(Offset(500f, 400f), overlayInnerCenterPx(1000, 800, 0, 0, 0, 0, spacingPx = 8))
    assertEquals(Offset(500f, 400f), overlayInnerCenterPx(1000, 800, 0, 0, 0, 0, spacingPx = 0))
  }

  @Test
  fun asymmetric_insets_keep_the_center_in_the_unobstructed_region() {
    // A 200px leading panel plus 8px overlay spacing on every edge.
    val center =
      overlayInnerCenterPx(
        overlayWidthPx = 1000,
        overlayHeightPx = 800,
        insetLeftPx = 200,
        insetTopPx = 10,
        insetRightPx = 20,
        insetBottomPx = 10,
        spacingPx = 8,
      )
    assertEquals(Offset(590f, 400f), center)
  }

  @Test
  fun the_query_point_matches_an_even_lens_and_ignores_an_odd_one() {
    val overlay = overlayInnerCenterPx(1000, 800, 0, 0, 0, 0, spacingPx = 0)
    val drag = Offset(16f, -12f)
    val evenChild = centeredChildCenter(IntSize(1000, 800), IntSize(220, 220))
    val oddChild = centeredChildCenter(IntSize(1000, 800), IntSize(221, 221))
    assertEquals(overlay + drag, evenChild + drag)
    assertNotEquals(overlay + drag, oddChild + drag)
  }

  @Test
  fun a_centered_childs_layout_center_oscillates_when_its_size_is_odd() {
    val parent = IntSize(1000, 800)
    val even = centeredChildCenter(parent, IntSize(220, 220))
    val odd = centeredChildCenter(parent, IntSize(221, 221))
    assertEquals(Offset(500f, 400f), even)
    assertNotEquals(even, odd, "odd sizes must not be used as a camera query point")
    assertEquals(0.5f, odd.x - even.x)
    assertEquals(0.5f, odd.y - even.y)
  }
}

/**
 * Compose [Alignment.Center] placement of [child] in [parent], then the child's geometric center.
 */
private fun centeredChildCenter(parent: IntSize, child: IntSize): Offset {
  val topLeft =
    Alignment.Center.align(size = child, space = parent, layoutDirection = LayoutDirection.Ltr)
  return Offset(topLeft.x + child.width / 2f, topLeft.y + child.height / 2f)
}
