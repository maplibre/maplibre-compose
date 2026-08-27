package org.maplibre.compose.util

import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageBitmapConversionTest {

  /** The color ints carry straight alpha, so a translucent pixel must not brighten or darken. */
  @Test
  fun a_straight_alpha_color_int_round_trips_through_the_platform_bitmap() {
    val halfRedHalfAlpha = 0x80800000.toInt()
    val pixel = intArrayOf(halfRedHalfAlpha).toImageBitmap(1, 1).toPixelMap()[0, 0]
    assertEquals(0.5f, pixel.alpha, 0.02f)
    assertEquals(0.5f, pixel.red, 0.02f)
    assertEquals(0.0f, pixel.green, 0.02f)
  }

  @Test
  fun an_opaque_color_int_round_trips_exactly() {
    val opaqueTeal = 0xFF008080.toInt()
    val pixel = intArrayOf(opaqueTeal).toImageBitmap(1, 1).toPixelMap()[0, 0]
    assertEquals(1.0f, pixel.alpha, 0.005f)
    assertEquals(0.0f, pixel.red, 0.005f)
    assertEquals(0x80 / 255f, pixel.green, 0.005f)
    assertEquals(0x80 / 255f, pixel.blue, 0.005f)
  }
}
