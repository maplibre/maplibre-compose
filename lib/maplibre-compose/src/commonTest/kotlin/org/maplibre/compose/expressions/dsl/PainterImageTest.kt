package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFailsWith

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

  private class TestPainter(override val intrinsicSize: Size) : Painter() {
    override fun DrawScope.onDraw() = Unit
  }
}
