package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.IntValue

/** A [Literal] representing an [Int] value. */
internal data class IntLiteral private constructor(override val value: Int) :
  Literal<IntValue, Int> {

  override fun compile(context: ExpressionContext): CompiledLiteral<IntValue, Float> =
    FloatLiteral.of(value.toFloat()).cast()

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  companion object {
    private val cache = IntCache(::IntLiteral)

    fun of(value: Int): IntLiteral = cache[value]
  }
}
