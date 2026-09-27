package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** An [Expression] representing a function call. */
public data class FunctionCall
private constructor(
  val name: String,
  val args: List<Expression<*>>,
  /** Argument indices encoded in literal context. */
  val literalArgs: Set<Int>,
) : Expression<ExpressionValue> {
  override fun compile(context: ExpressionContext): CompiledExpression<ExpressionValue> =
    CompiledFunctionCall.of(name, args.map { it.compile(context) }, literalArgs)

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    args.forEach { it.visit(block) }
  }

  public companion object {
    /**
     * Creates a call with snapshots of [args] and [literalArgs]. Later changes to the collections
     * do not affect the call.
     */
    public fun of(
      name: String,
      args: List<Expression<*>>,
      literalArgs: Set<Int> = emptySet(),
    ): FunctionCall = FunctionCall(name, args.toList(), literalArgs.toSet())

    public fun of(
      name: String,
      vararg args: Expression<*>,
      literalArgs: Set<Int> = emptySet(),
    ): FunctionCall = of(name, args.asList(), literalArgs)
  }
}
