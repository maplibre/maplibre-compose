package org.maplibre.compose.expressions.ast

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import org.maplibre.compose.expressions.value.DpOffsetValue

/** A [Literal] representing a [DpOffset] value. */
public data class DpOffsetLiteral private constructor(override val value: DpOffset) :
  Literal<DpOffsetValue, DpOffset> {
  override fun compile(context: ExpressionContext): CompiledLiteral<DpOffsetValue, Offset> =
    OffsetLiteral.of(Offset(value.x.value, value.y.value)).cast()

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    private val zero = DpOffsetLiteral(DpOffset.Zero)

    public fun of(value: DpOffset): DpOffsetLiteral =
      if (value == DpOffset.Zero) zero else DpOffsetLiteral(value)
  }
}
