package org.maplibre.compose.expressions.ast

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.util.ImageResizeOptions

/**
 * A [Literal] representing a [Painter] value, which will be drawn to a bitmap and loaded as an
 * image into the style upon compilation.
 */
public data class PainterLiteral
private constructor(
  override val value: Painter,
  val size: DpSize?,
  val sdf: Boolean,
  val resizeOptions: ImageResizeOptions?,
  val alpha: Float,
  val colorFilter: ColorFilter?,
) : Literal<StringValue, Painter> {
  override fun compile(context: ExpressionContext): StringLiteral =
    StringLiteral.of(context.resolvePainter(this))

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    public fun of(
      value: Painter,
      size: DpSize?,
      drawAsSdf: Boolean,
      resizeOptions: ImageResizeOptions?,
      alpha: Float = DefaultAlpha,
      colorFilter: ColorFilter? = null,
    ): PainterLiteral = PainterLiteral(value, size, drawAsSdf, resizeOptions, alpha, colorFilter)
  }
}
