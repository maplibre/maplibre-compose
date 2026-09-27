package org.maplibre.compose.util

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.style.ImageSnapshot

/**
 * Native style images and image sources share [toPremultipliedRgba8]. MapLibre Native blends those
 * buffers as already scaled by alpha, so Compose scales the colour channels here.
 */
class ImagePremultiplyTest {

  @Test
  fun bitmap_and_snapshot_pixels_are_premultiplied_once() {
    val bitmap = ImageBitmap(1, 1)
    Canvas(bitmap).drawRect(Rect(0f, 0f, 1f, 1f), Paint().apply { color = Color(255, 0, 0, 128) })

    val images =
      listOf(bitmap.toPremultipliedRgba8(), ImageSnapshot.capture(bitmap).toPremultipliedRgba8())
    for (image in images) {
      val pixels = image.pixels
      assertEquals(128, pixels[0].toUByte().toInt(), "red")
      assertEquals(0, pixels[1].toUByte().toInt(), "green")
      assertEquals(0, pixels[2].toUByte().toInt(), "blue")
      assertEquals(128, pixels[3].toUByte().toInt(), "alpha")
    }
  }
}
