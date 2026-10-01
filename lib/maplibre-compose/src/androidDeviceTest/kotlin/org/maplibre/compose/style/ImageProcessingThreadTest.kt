package org.maplibre.compose.style

import android.os.Looper
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.util.prepareInEngineContext
import org.maplibre.compose.util.toImageBitmap

class ImageProcessingThreadTest {
  @Test
  fun painter_drawing_stays_on_main_when_called_from_a_worker() =
    runBlocking(Dispatchers.Default) {
      val painter =
        object : Painter() {
          override val intrinsicSize = Size(1f, 1f)

          override fun DrawScope.onDraw() {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            drawRect(Color.Red)
          }
        }
      val image = ResolvedStyleImage.fromPainter(painter, Density(1f), LayoutDirection.Ltr)
      val pixels = IntArray(1)
      image.image.toImageBitmap().readPixels(pixels)
      assertEquals(0xffff0000.toInt(), pixels.single())
    }

  @Test
  fun image_preparation_reads_pixels_off_main() = runBlocking {
    val bitmap = BackgroundReadBitmap(intArrayOf(0x80ff0000.toInt(), 0).toImageBitmap(2, 1))
    val prepared = withContext(Dispatchers.Main) { prepareInEngineContext(bitmap) }
    val pixels = IntArray(2)
    prepared.toImageBitmap().readPixels(pixels)
    assertEquals(listOf(0x80ff0000.toInt(), 0), pixels.toList())
  }

  @Test
  fun sdf_conversion_reads_pixels_off_main() = runBlocking {
    val bitmap = BackgroundReadBitmap(intArrayOf(0xffffffff.toInt()).toImageBitmap(1, 1))
    val sdf = withContext(Dispatchers.Main) { bitmap.toSdf() }
    assertEquals(13, sdf.width)
    assertEquals(13, sdf.height)
    val pixels = IntArray(13 * 13)
    sdf.readPixels(pixels)
    assertTrue((pixels[6 * 13 + 6] ushr 24) > (pixels[0] ushr 24))
  }

  /** Fails at the pixel read rather than relying on timing to detect main-thread work. */
  private class BackgroundReadBitmap(private val bitmap: ImageBitmap) : ImageBitmap by bitmap {
    override fun readPixels(
      buffer: IntArray,
      startX: Int,
      startY: Int,
      width: Int,
      height: Int,
      bufferOffset: Int,
      stride: Int,
    ) {
      assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
      bitmap.readPixels(buffer, startX, startY, width, height, bufferOffset, stride)
    }
  }
}
