package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class PainterImageTest {
  @Test
  fun zero_intrinsic_size_requires_an_explicit_size() {
    val error = assertFailsWith<IllegalArgumentException> { image(TestPainter(Size.Zero)) }

    assertContains(
      error.message.orEmpty(),
      "Painter image size must have positive width and height",
    )
    assertContains(error.message.orEmpty(), "Pass a size with positive width and height to image()")
  }

  @Test
  fun explicit_positive_size_accepts_a_zero_intrinsic_size() {
    image(TestPainter(Size.Zero), size = DpSize(24.dp, 24.dp))
  }

  @Test
  fun unspecified_intrinsic_size_uses_the_default_size() {
    image(TestPainter(Size.Unspecified))
  }

  @Test
  fun explicit_zero_size_is_rejected() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        image(TestPainter(Size.Unspecified), size = DpSize.Zero)
      }

    assertContains(
      error.message.orEmpty(),
      "Painter image size must have positive width and height",
    )
  }

  private class TestPainter(override val intrinsicSize: Size) : Painter() {
    override fun DrawScope.onDraw() = Unit
  }
}
