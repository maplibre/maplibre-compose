package org.maplibre.compose.style

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Draws painters to real bitmaps, which the Android host JVM cannot create. */
class ImageManagerPainterTest {

  @Test
  fun distinct_painters_with_identical_pixels_share_one_image() {
    val manager = ImageManager(StyleNode(RecordingStyleBinding()))
    val first = painterKey(SolidPainter(Color.Red))
    val second = painterKey(SolidPainter(Color.Red))

    val firstId = manager.acquirePainter(first)
    val secondId = manager.acquirePainter(second)

    assertEquals(firstId, secondId)
    assertEquals(listOf(firstId), manager.desiredImages.map { it.id })

    manager.releasePainter(first)
    assertEquals(listOf(firstId), manager.desiredImages.map { it.id })

    manager.releasePainter(second)
    assertTrue(manager.desiredImages.isEmpty())
  }

  @Test
  fun painters_with_different_pixels_get_separate_images() {
    val manager = ImageManager(StyleNode(RecordingStyleBinding()))

    val redId = manager.acquirePainter(painterKey(SolidPainter(Color.Red)))
    val blueId = manager.acquirePainter(painterKey(SolidPainter(Color.Blue)))

    assertNotEquals(redId, blueId)
    assertEquals(2, manager.desiredImages.size)
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
