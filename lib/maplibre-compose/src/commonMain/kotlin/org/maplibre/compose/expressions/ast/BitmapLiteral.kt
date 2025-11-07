package org.maplibre.compose.expressions.ast

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.util.ImageResizeOptions

/**
 * A [Literal] representing an [ImageBitmap] value, which will be loaded as an image into the style
 * upon compilation.
 */
public data class BitmapLiteral
private constructor(
  override val value: ImageBitmap,
  val sdf: Boolean,
  val resizeOptions: ImageResizeOptions?,
) : Literal<StringValue, ImageBitmap> {
  override fun compile(context: ExpressionContext): StringLiteral =
    StringLiteral.of(context.resolveBitmap(this))

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    public fun of(
      value: ImageBitmap,
      isSdf: Boolean,
      resizeOptions: ImageResizeOptions?,
    ): BitmapLiteral = BitmapLiteral(value, isSdf, resizeOptions)
  }
}
