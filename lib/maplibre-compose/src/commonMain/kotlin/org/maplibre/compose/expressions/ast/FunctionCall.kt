package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** An [Expression] representing a function call. */
public data class FunctionCall
private constructor(
  val name: String,
  val args: List<Expression<*>>,
  private val literalArgs: Set<Int>,
) : Expression<ExpressionValue> {
  /** Whether an argument is encoded in literal context, captured when the call is created. */
  public val isLiteralArg: (Int) -> Boolean = { it in literalArgs }

  override fun compile(context: ExpressionContext): CompiledExpression<ExpressionValue> =
    CompiledFunctionCall.of(name, args.map { it.compile(context) }, isLiteralArg)

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    args.forEach { it.visit(block) }
  }

  public companion object {
    /**
     * Creates a call with snapshots of [args] and [isLiteralArg] for its argument positions. Later
     * changes to the list or predicate state do not affect the call.
     */
    public fun of(
      name: String,
      args: List<Expression<*>>,
      isLiteralArg: (Int) -> Boolean = { false },
    ): FunctionCall =
      FunctionCall(name, args.toList(), args.indices.filterTo(mutableSetOf(), isLiteralArg))

    public fun of(
      name: String,
      vararg args: Expression<*>,
      isLiteralArg: (Int) -> Boolean = { false },
    ): FunctionCall = of(name, args.asList(), isLiteralArg)
  }
}
