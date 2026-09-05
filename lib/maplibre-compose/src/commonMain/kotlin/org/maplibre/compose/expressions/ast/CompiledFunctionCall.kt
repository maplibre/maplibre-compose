package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** A function call with compiled arguments. */
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
