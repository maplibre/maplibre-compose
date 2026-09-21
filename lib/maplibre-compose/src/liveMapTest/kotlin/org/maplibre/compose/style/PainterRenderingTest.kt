package org.maplibre.compose.style

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.testing.runGraphicsTest

/** Draws painters to real bitmaps, which the Android host JVM cannot create. */
class PainterRenderingTest {

  @Test
  fun capture_preserves_pixel_rows_at_non_aligned_widths() = runGraphicsTest { graphics ->
    val painter =
      object : Painter() {
        override val intrinsicSize = Size(7f, 3f)

        override fun DrawScope.onDraw() {
          drawRect(Color.Red)
          drawRect(Color.Blue, topLeft = Offset(0f, 1f), size = Size(7f, 2f))
        }
      }
    val bitmap = capture(painter, graphics)
    assertEquals(7, bitmap.width)
    assertEquals(3, bitmap.height)
    val pixels = IntArray(21)
    bitmap.readPixels(pixels)
    assertEquals(List(7) { 0xffff0000.toInt() } + List(14) { 0xff0000ff.toInt() }, pixels.toList())
  }

  @Test
  fun cancelled_capture_releases_layer_and_can_be_retried() = runGraphicsTest { graphics ->
    var activeLayers = 0
    val tracking =
      object : GraphicsContext by graphics {
        override fun createGraphicsLayer(): GraphicsLayer =
          graphics.createGraphicsLayer().also { activeLayers++ }

        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
          graphics.releaseGraphicsLayer(layer)
          activeLayers--
        }
      }
    var cancel = true
    val painter =
      object : Painter() {
        override val intrinsicSize = Size(2f, 2f)

        override fun DrawScope.onDraw() {
          if (cancel) throw CancellationException("cancel capture")
          drawRect(Color.Red)
        }
      }
    assertFailsWith<CancellationException> { capture(painter, tracking) }
    assertEquals(0, activeLayers)
    cancel = false
    capture(painter, tracking)
    assertEquals(0, activeLayers)
  }

  private suspend fun capture(painter: Painter, graphics: GraphicsContext) =
    renderPainter(
      painter,
      graphics,
      Density(1f),
      LayoutDirection.Ltr,
      null,
      false,
      DefaultAlpha,
      null,
    )
}
