package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.FloatValue

/**
 * The context used while converting a high-level [Expression] to a low-level [CompiledExpression].
 *
 * It defines how to resolve certain expressions (TextUnit, bitmaps) to their MapLibre counterparts.
 * MapLibre Compose users should not need to implement this interface; it is used internally by the
 * MapLibre Compose library.
 */
public interface ExpressionContext {
  /** The scale factor to convert EMs to the desired unit */
  public val emScale: Expression<FloatValue>

  /** The scale factor to convert SPs to the desired unit */
  public val spScale: Expression<FloatValue>

  /** @return the resolved identifier for the [bitmap]. */
  public fun resolveBitmap(bitmap: BitmapLiteral): String

  /** @return the resolved identifier for the [painter]. */
  public fun resolvePainter(painter: PainterLiteral): String

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
  }
}
