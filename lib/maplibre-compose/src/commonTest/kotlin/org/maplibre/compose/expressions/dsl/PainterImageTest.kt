package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.maplibre.compose.map.FakeImageBitmap

class PainterImageTest {
  @Test
  fun painter_size_must_be_positive_or_unspecified() {
    assertFailsWith<IllegalArgumentException> { image(TestPainter(Size.Zero)) }
    assertFailsWith<IllegalArgumentException> {
      image(TestPainter(Size.Unspecified), size = DpSize.Zero)
    }
    image(TestPainter(Size.Zero), size = DpSize(24.dp, 24.dp))
    image(TestPainter(Size.Unspecified))
  }

  @Test
  fun bitmap_size_must_be_positive() {
    assertFailsWith<IllegalArgumentException> { image(FakeImageBitmap(0, 1)) }
    assertFailsWith<IllegalArgumentException> { image(FakeImageBitmap(1, 0)) }
    image(FakeImageBitmap(1, 1))
  }

  private class TestPainter(override val intrinsicSize: Size) : Painter() {
    override fun DrawScope.onDraw() = Unit
  }
}
