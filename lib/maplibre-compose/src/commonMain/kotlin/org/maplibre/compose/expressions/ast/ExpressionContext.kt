package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.FloatValue

/**
 * Resolves text units, images, and fonts when compiling an [Expression].
 *
 * The library supplies this context; applications do not need to implement it.
 */
public interface ExpressionContext {
  /** The scale factor to convert EMs to the desired unit */
  public val emScale: Expression<FloatValue>

  /** The scale factor to convert SPs to the desired unit */
  public val spScale: Expression<FloatValue>

  /** The scale factor to convert DP text offsets to the desired text unit. */
  public val dpScale: Expression<FloatValue>
    get() = error("DP text offsets are not allowed in this context")

  /** @return the resolved identifier for the [bitmap]. */
  public fun resolveBitmap(bitmap: BitmapLiteral): String

  /** @return the resolved identifier for the [painter]. */
  public fun resolvePainter(painter: PainterLiteral): String

  /** @return the font stack that [font] compiles to, after registering its file. */
  public fun resolveFont(font: FontLiteral): List<String>

  /** A context where no complex types can be resolved. */
  public object None : ExpressionContext {
    override val emScale: Expression<FloatValue>
      get() = error("TextUnit not allowed in this context")

    override val spScale: Expression<FloatValue>
      get() = error("TextUnit not allowed in this context")

    override fun resolveBitmap(bitmap: BitmapLiteral): String =
      error("Bitmap not allowed in this context")

    override fun resolvePainter(painter: PainterLiteral): String =
      error("Painter not allowed in this context")

    override fun resolveFont(font: FontLiteral): List<String> =
      error("Font not allowed in this context")
  }
}
