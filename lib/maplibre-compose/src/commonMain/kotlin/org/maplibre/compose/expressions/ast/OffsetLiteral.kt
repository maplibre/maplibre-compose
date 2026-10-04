package org.maplibre.compose.expressions.ast

import androidx.compose.ui.geometry.Offset
import org.maplibre.compose.expressions.value.FloatOffsetValue

/** A [Literal] representing a [Offset] value. */
internal data class OffsetLiteral private constructor(override val value: Offset) :
  CompiledLiteral<FloatOffsetValue, Offset> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  companion object {
    private val zero = OffsetLiteral(Offset.Zero)

    fun of(value: Offset): OffsetLiteral = if (value == Offset.Zero) zero else OffsetLiteral(value)
  }
}
