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
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.ImageStretch

class PainterStyleImageTest {
  @Test
  fun painter_registration_returns_a_removable_image_and_rejects_duplicates() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val images = fixture.state.style.images
      val handle = images.add("marker", ColorPainter(Color.Red), Density(2f), LayoutDirection.Rtl)
      assertNotNull(images["marker"])
      assertFailsWith<StyleHandleException> {
        images.add("marker", ColorPainter(Color.Blue), Density(2f), LayoutDirection.Rtl)
      }
      assertTrue(handle.remove())
      assertNull(images["marker"])
    }
  }

  @Test
  fun standalone_rendering_uses_explicit_environment_and_drawing_options() = runMapTest {
    createMapFixture().use {
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
      assertEquals(6, resolved.image.width)
      assertEquals(4, resolved.image.height)
      assertEquals(stretch, resolved.stretch)
      val pixel = IntArray(1)
      resolved.image.readPixels(pixel, width = 1, height = 1)
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
      // The 16 DP fallback is 32 pixels, with a six-pixel SDF border on every side.
      assertEquals(44, sdf.image.width)
      assertEquals(44, sdf.image.height)
    }
  }

  @Test
  fun a_style_change_during_rendering_rejects_registration() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val painter =
        object : Painter() {
          override val intrinsicSize = Size(2f, 2f)

          override fun DrawScope.onDraw() {
            fixture.state.style.asMutable!!.baseStyle =
              BaseStyle.Json("""{"version":8,"name":"replacement","sources":{},"layers":[]}""")
            drawRect(Color.Red)
          }
        }
      assertFailsWith<IllegalStateException> {
        fixture.state.style.images.add("stale", painter, Density(1f), LayoutDirection.Ltr)
      }
      fixture.loadStyle(fixture.state.style.baseStyle)
      assertNull(fixture.state.style.images["stale"])
    }
  }

  @Test
  fun cancelled_rendering_does_not_register_or_reserve_the_id() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val painter =
        object : Painter() {
          override val intrinsicSize = Size(2f, 2f)

          override fun DrawScope.onDraw() {
            throw CancellationException("cancel capture")
          }
        }
      val images = fixture.state.style.images
      assertFailsWith<CancellationException> {
        images.add("marker", painter, Density(1f), LayoutDirection.Ltr)
      }
      assertNull(images["marker"])
      assertTrue(
        images.add("marker", ColorPainter(Color.Red), Density(1f), LayoutDirection.Ltr).remove()
      )
    }
  }
}
