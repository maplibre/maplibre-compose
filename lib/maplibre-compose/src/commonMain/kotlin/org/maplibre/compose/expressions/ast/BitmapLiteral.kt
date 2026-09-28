package org.maplibre.compose.expressions.ast

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.util.ImageStretch

/**
 * A [Literal] representing an [ImageBitmap] value, which will be loaded as an image into the style
 * upon compilation.
 */
public data class BitmapLiteral
private constructor(
  override val value: ImageBitmap,
  val sdf: Boolean,
  val stretch: ImageStretch?,
) : Literal<StringValue, ImageBitmap> {
  override fun compile(context: ExpressionContext): StringLiteral =
    StringLiteral.of(context.resolveBitmap(this))

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    public fun of(value: ImageBitmap, isSdf: Boolean, stretch: ImageStretch?): BitmapLiteral {
      require(value.width > 0 && value.height > 0) {
        "Bitmap image size must have positive width and height, but was " +
          "${value.width}x${value.height}."
      }
      return BitmapLiteral(value, isSdf, stretch)
    }
  }
}
