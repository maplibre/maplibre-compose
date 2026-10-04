package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** An [Expression] representing a function call. */
internal data class FunctionCall
private constructor(val name: String, val args: List<CallArgument>) :
  ExpressionNode<ExpressionValue> {
  override fun compile(context: ExpressionContext): CompiledExpression<ExpressionValue> =
    CompiledFunctionCall.of(name, args.map { it.compileArgument(context) })

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    args.forEach { it.visitArgument(block) }
  }

  companion object {
    /**
     * Creates a call with a snapshot of [args]. Later changes to the list do not affect the call.
     */
    fun of(name: String, args: List<CallArgument>): FunctionCall = FunctionCall(name, args.toList())
  }
}
