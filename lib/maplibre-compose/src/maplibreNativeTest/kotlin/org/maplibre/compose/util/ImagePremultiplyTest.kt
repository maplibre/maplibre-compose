package org.maplibre.compose.util

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native style images and image sources upload [PreparedImage] bytes as they are. MapLibre Native
 * blends those bytes as already scaled by alpha, so preparation scales the colour channels once.
 */
class ImagePremultiplyTest {

  @Test
  fun prepared_pixels_are_premultiplied_once() {
    val bitmap = ImageBitmap(1, 1)
    Canvas(bitmap).drawRect(Rect(0f, 0f, 1f, 1f), Paint().apply { color = Color(255, 0, 0, 128) })

    val pixels = PreparedImage.fromBitmap(bitmap).pixels.ffi.pixels
    assertEquals(128, pixels[0].toUByte().toInt(), "red")
    assertEquals(0, pixels[1].toUByte().toInt(), "green")
    assertEquals(0, pixels[2].toUByte().toInt(), "blue")
    assertEquals(128, pixels[3].toUByte().toInt(), "alpha")
  }

  @Test
  fun unpremultiplying_then_premultiplying_restores_every_channel() {
    for (alpha in 0..255) {
      for (channel in 0..alpha) {
        assertEquals(
          channel,
          premultiplyChannel(unpremultiplyChannel(channel, alpha), alpha),
          "channel $channel at alpha $alpha",
        )
      }
    }
  }

  /** Skia rounds as preparation does, so its round trip is exact; Android may differ by one. */
  @Test
  fun a_translucent_image_survives_a_bitmap_round_trip() {
    val argb =
      IntArray(256) { index -> (index shl 24) or (255 shl 16) or ((255 - index) shl 8) or 64 }
    val prepared = PreparedImage(enginePixels(16, 16, argb))

    val restored = PreparedImage.fromBitmap(prepared.toImageBitmap())

    val expected = prepared.pixels.ffi.pixels
    val actual = restored.pixels.ffi.pixels
    assertEquals(expected.size, actual.size)
    for (index in expected.indices) {
      val difference = abs(expected[index].toUByte().toInt() - actual[index].toUByte().toInt())
      assertTrue(difference <= 1, "byte $index differs by $difference")
    }
  }
}
