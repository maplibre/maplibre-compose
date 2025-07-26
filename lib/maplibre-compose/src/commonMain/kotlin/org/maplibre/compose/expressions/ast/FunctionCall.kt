package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** An [Expression] representing a function call. */
public data class FunctionCall
private constructor(
  val name: String,
  val args: List<Expression<*>>,
  val isLiteralArg: (Int) -> Boolean,
) : Expression<ExpressionValue> {
  override fun compile(context: ExpressionContext): CompiledExpression<ExpressionValue> =
    CompiledFunctionCall.of(name, args.map { it.compile(context) }, isLiteralArg)

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    args.forEach { it.visit(block) }
  }

  public companion object {
    public fun of(
      name: String,
      vararg args: Expression<*>,
      isLiteralArg: (Int) -> Boolean = { false },
    ): FunctionCall = FunctionCall(name, args.asList(), isLiteralArg)
  }
}
