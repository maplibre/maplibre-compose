package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** A [Literal] representing an function call with args all of [CompiledExpression] */
public data class CompiledFunctionCall
private constructor(
  val name: String,
  val args: List<CompiledExpression<*>>,
  val isLiteralArg: (Int) -> Boolean,
) : CompiledExpression<ExpressionValue> {
  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    args.forEach { it.visit(block) }
  }

  public companion object {
    public fun of(
      name: String,
      args: List<CompiledExpression<*>>,
      isLiteralArg: (Int) -> Boolean = { false },
    ): CompiledFunctionCall = CompiledFunctionCall(name, args, isLiteralArg)
  }
}
