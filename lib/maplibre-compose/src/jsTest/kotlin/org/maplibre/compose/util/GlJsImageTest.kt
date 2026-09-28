package org.maplibre.compose.util

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GL JS premultiplies at texture upload. Compose leaves the colour channels straight; scaling them
 * during preparation would darken a translucent image to a quarter after WebGL does it again.
 */
class GlJsImageTest {

  @Test
  fun a_translucent_bitmap_is_prepared_straight() {
    val bitmap = ImageBitmap(1, 1)
    Canvas(bitmap).drawRect(Rect(0f, 0f, 1f, 1f), Paint().apply { color = Color(255, 0, 0, 128) })

    val pixels = PreparedImage.fromBitmap(bitmap).pixels.styleImageData().data
    assertEquals(255, pixels[0].toInt() and 0xFF, "red")
    assertEquals(0, pixels[1].toInt() and 0xFF, "green")
    assertEquals(0, pixels[2].toInt() and 0xFF, "blue")
    assertEquals(128, pixels[3].toInt() and 0xFF, "alpha")
  }
}
