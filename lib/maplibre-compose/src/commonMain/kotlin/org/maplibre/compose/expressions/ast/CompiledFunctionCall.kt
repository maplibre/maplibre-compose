package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** A function call with compiled arguments. */
public data class CompiledFunctionCall
private constructor(
  val name: String,
  val args: List<CompiledExpression<*>>,
  private val literalArgs: Set<Int>,
) : CompiledExpression<ExpressionValue> {
  /** Whether an argument is encoded in literal context, captured when the call is created. */
  public val isLiteralArg: (Int) -> Boolean = { it in literalArgs }

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    args.forEach { it.visit(block) }
  }

  public companion object {
    public fun of(
      name: String,
      args: List<CompiledExpression<*>>,
      isLiteralArg: (Int) -> Boolean = { false },
    ): CompiledFunctionCall =
      CompiledFunctionCall(name, args.toList(), args.indices.filterTo(mutableSetOf(), isLiteralArg))
  }
}
