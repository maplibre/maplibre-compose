package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.DpPaddingValue
import org.maplibre.compose.util.DpPadding

/** A [Literal] representing a [DpPadding] value. */
internal data class DpPaddingLiteral private constructor(override val value: DpPadding) :
  CompiledLiteral<DpPaddingValue, DpPadding> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  companion object {
    private val zero = DpPaddingLiteral(DpPadding.Zero)

    fun of(value: DpPadding): DpPaddingLiteral =
      if (value == DpPadding.Zero) zero else DpPaddingLiteral(value)
  }
}
