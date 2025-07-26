package org.maplibre.compose.expressions.ast

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.value.DpPaddingValue

/** A [Literal] representing a [PaddingValues] value. */
public data class DpPaddingLiteral private constructor(override val value: PaddingValues.Absolute) :
  CompiledLiteral<DpPaddingValue, PaddingValues.Absolute> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    private val zero = DpPaddingLiteral(PaddingValues.Absolute(0.dp, 0.dp, 0.dp, 0.dp))

    public fun of(value: PaddingValues.Absolute): DpPaddingLiteral =
      if (value == zero.value) zero else DpPaddingLiteral(value)
  }
}
