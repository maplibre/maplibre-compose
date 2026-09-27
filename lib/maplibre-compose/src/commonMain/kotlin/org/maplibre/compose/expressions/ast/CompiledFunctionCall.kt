package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** A function call with compiled arguments. */
public data class CompiledFunctionCall
private constructor(
  val name: String,
  val args: List<CompiledExpression<*>>,
  /** Argument indices encoded in literal context. */
  val literalArgs: Set<Int>,
) : CompiledExpression<ExpressionValue> {
  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    args.forEach { it.visit(block) }
  }

  public companion object {
    public fun of(
      name: String,
      args: List<CompiledExpression<*>>,
      literalArgs: Set<Int> = emptySet(),
    ): CompiledFunctionCall = CompiledFunctionCall(name, args.toList(), literalArgs.toSet())
  }
}
