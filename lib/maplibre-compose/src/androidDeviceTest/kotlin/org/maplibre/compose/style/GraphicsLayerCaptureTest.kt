package org.maplibre.compose.style

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.testing.runGraphicsTest

class GraphicsLayerCaptureTest {
  @Test
  fun image_reader_capture_preserves_translucent_colors_and_padded_rows() =
    runGraphicsTest { graphics ->
      val density = Density(1f)
      val direction = LayoutDirection.Ltr
      val layer = graphics.createGraphicsLayer()
      try {
        layer.record(density, direction, IntSize(7, 3)) {
          drawRect(Color.Red, size = Size(7f, 1f), alpha = 0.5f)
          drawRect(Color.Blue, topLeft = Offset(0f, 1f), size = Size(7f, 1f), alpha = 0.25f)
        }
        // Exercise the API 24-27 path on newer Android versions too.
        val bitmap = layer.captureWithImageReader(density, direction)
        val pixels = IntArray(21)
        bitmap.readPixels(pixels)
        for (pixel in pixels.take(7)) {
          assertEquals(0xff0000, pixel and 0xffffff)
          assertTrue((pixel ushr 24) in 127..128)
        }
        for (pixel in pixels.drop(7).take(7)) {
          assertEquals(0x0000ff, pixel and 0xffffff)
          assertTrue((pixel ushr 24) in 63..64)
        }
        assertEquals(List(7) { 0 }, pixels.drop(14))
      } finally {
        graphics.releaseGraphicsLayer(layer)
      }
    }
}
