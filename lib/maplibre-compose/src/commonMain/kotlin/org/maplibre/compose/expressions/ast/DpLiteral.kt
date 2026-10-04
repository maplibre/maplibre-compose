package org.maplibre.compose.expressions.ast

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.value.DpValue

/** A [Literal] representing a [Dp] value. */
internal data class DpLiteral private constructor(override val value: Dp) : Literal<DpValue, Dp> {

  override fun compile(context: ExpressionContext): CompiledLiteral<DpValue, Float> =
    FloatLiteral.of(value.value).cast()

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  companion object {
    private val cache = FloatCache { DpLiteral(it.dp) }

    fun of(value: Dp): DpLiteral = cache[value.value]
  }
}
