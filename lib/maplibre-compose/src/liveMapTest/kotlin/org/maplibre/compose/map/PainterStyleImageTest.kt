package org.maplibre.compose.map

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.ImageStretch

class PainterStyleImageTest {
  @Test
  fun positive_subpixel_sizes_produce_nonempty_images() = runTest {
    val painter =
      object : Painter() {
        override val intrinsicSize = Size(0.5f, 0.5f)

        override fun DrawScope.onDraw() = drawRect(Color.Red)
      }
    for (size in listOf(null, DpSize(0.5.dp, 0.5.dp))) {
      val resolved =
        ResolvedStyleImage.fromPainter(
          painter,
          Density(1f),
          LayoutDirection.Ltr,
          size = size,
        )
      assertEquals(1, resolved.width)
      assertEquals(1, resolved.height)
    }
  }

  @Test
  fun painter_registration_returns_a_removable_image_and_replaces_in_place() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val images = fixture.state.style.images
      images.set(
        "marker",
        ResolvedStyleImage.fromPainter(ColorPainter(Color.Red), Density(2f), LayoutDirection.Rtl),
      )
      val handle = assertNotNull(images["marker"]?.asMutable)
      images.set(
        "marker",
        ResolvedStyleImage.fromPainter(ColorPainter(Color.Blue), Density(2f), LayoutDirection.Rtl),
      )
      val replacement = assertNotNull(images["marker"]?.asMutable)
      assertFailsWith<IllegalStateException> { handle.remove() }
      replacement.remove()
      assertNull(images["marker"])
    }
  }

  @Test
  fun standalone_rendering_uses_explicit_environment_and_drawing_options() = runTest {
    val painter =
      object : Painter() {
        override val intrinsicSize = Size.Unspecified

        override fun DrawScope.onDraw() {
          assertEquals(2f, density)
          assertEquals(LayoutDirection.Rtl, layoutDirection)
          drawRect(Color.Red)
        }
      }
    val stretch = ImageStretch.capInsets(1.dp, 1.dp, 1.dp, 1.dp)
    val resolved =
      ResolvedStyleImage.fromPainter(
        painter,
        Density(2f),
        LayoutDirection.Rtl,
        size = DpSize(3.dp, 2.dp),
        stretch = stretch,
        alpha = 0.5f,
        colorFilter = ColorFilter.tint(Color.Blue),
      )
    assertEquals(6, resolved.width)
    assertEquals(4, resolved.height)
    assertEquals(stretch, resolved.stretch)
    val pixel = IntArray(1)
    resolved.toImageBitmap().readPixels(pixel, width = 1, height = 1)
    assertEquals(0xff, pixel[0] and 0xffffff)
    assertTrue((pixel[0] ushr 24) in 127..128)

    val sdf =
      ResolvedStyleImage.fromPainter(
        ColorPainter(Color.White),
        Density(2f),
        LayoutDirection.Ltr,
        drawAsSdf = true,
      )
    assertTrue(sdf.sdf)
    assertTrue(sdf.width > 0 && sdf.height > 0)
  }
}
