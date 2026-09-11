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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.testing.runGraphicsTest

/** Draws painters to real bitmaps, which the Android host JVM cannot create. */
class ImageManagerPainterTest {

  @Test
  fun distinct_painters_with_identical_pixels_share_one_image() = runGraphicsTest { graphics ->
    val manager = ImageManager(StyleNode(RecordingStyleBinding()))
    val first = painterKey(SolidPainter(Color.Red))
    val second = painterKey(SolidPainter(Color.Red))

    val firstId = manager.acquirePainter(first, graphics)
    val secondId = manager.acquirePainter(second, graphics)

    assertEquals(firstId, secondId)
    assertEquals(listOf(firstId), manager.desiredImages.map { it.id })

    manager.releasePainter(first)
    assertEquals(listOf(firstId), manager.desiredImages.map { it.id })

    manager.releasePainter(second)
    assertTrue(manager.desiredImages.isEmpty())
  }

  @Test
  fun painters_with_different_pixels_get_separate_images() = runGraphicsTest { graphics ->
    val manager = ImageManager(StyleNode(RecordingStyleBinding()))

    val redId = manager.acquirePainter(painterKey(SolidPainter(Color.Red)), graphics)
    val blueId = manager.acquirePainter(painterKey(SolidPainter(Color.Blue)), graphics)

    assertNotEquals(redId, blueId)
    assertEquals(2, manager.desiredImages.size)
  }

  @Test
  fun repeated_key_is_drawn_once_and_kept_until_last_release() = runGraphicsTest { graphics ->
    val manager = ImageManager(StyleNode(RecordingStyleBinding()))
    var draws = 0
    val painter =
      object : Painter() {
        override val intrinsicSize = Size(2f, 2f)

        override fun DrawScope.onDraw() {
          draws++
          drawRect(Color.Red)
        }
      }
    val key = painterKey(painter)
    val id = manager.acquirePainter(key, graphics)
    assertEquals(id, manager.acquirePainter(key, graphics))
    assertEquals(1, draws)
    manager.releasePainter(key)
    assertEquals(listOf(id), manager.desiredImages.map { it.id })
    manager.releasePainter(key)
    assertTrue(manager.desiredImages.isEmpty())
  }

  @Test
  fun capture_preserves_pixel_rows_at_non_aligned_widths() = runGraphicsTest { graphics ->
    val manager = ImageManager(StyleNode(RecordingStyleBinding()))
    val painter =
      object : Painter() {
        override val intrinsicSize = Size(7f, 3f)

        override fun DrawScope.onDraw() {
          drawRect(Color.Red)
          drawRect(Color.Blue, topLeft = Offset(0f, 1f), size = Size(7f, 2f))
        }
      }
    val key = painterKey(painter)
    manager.acquirePainter(key, graphics)
    val bitmap = manager.desiredImages.single().image.toImageBitmap()
    assertEquals(7, bitmap.width)
    assertEquals(3, bitmap.height)
    val pixels = IntArray(21)
    bitmap.readPixels(pixels)
    assertEquals(List(7) { 0xffff0000.toInt() } + List(14) { 0xff0000ff.toInt() }, pixels.toList())
    manager.releasePainter(key)
  }

  @Test
  fun cancelled_capture_releases_layer_and_does_not_poison_cache() = runGraphicsTest { graphics ->
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
    val manager = ImageManager(StyleNode(RecordingStyleBinding()))
    val key = painterKey(painter)
    assertFailsWith<CancellationException> { manager.acquirePainter(key, tracking) }
    assertEquals(0, activeLayers)
    assertTrue(manager.desiredImages.isEmpty())
    cancel = false
    manager.acquirePainter(key, tracking)
    assertEquals(0, activeLayers)
    assertEquals(1, manager.desiredImages.size)
    manager.releasePainter(key)
    assertTrue(manager.desiredImages.isEmpty())
  }

  private fun painterKey(painter: Painter) =
    ImageManager.PainterKey(
      painter = painter,
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      size = null,
      drawAsSdf = false,
      stretch = null,
      alpha = DefaultAlpha,
      colorFilter = null,
    )

  private class SolidPainter(private val color: Color) : Painter() {
    override val intrinsicSize: Size = Size(2f, 2f)

    override fun DrawScope.onDraw() = drawRect(color)
  }
}
