package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** A function call with compiled arguments. */
internal data class CompiledFunctionCall
private constructor(val name: String, val args: List<CompiledCallArgument>) :
  CompiledExpression<ExpressionValue> {
  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    args.forEach { it.visitArgument(block) }
  }

  companion object {
    fun of(name: String, args: List<CompiledCallArgument>): CompiledFunctionCall =
      CompiledFunctionCall(name, args.toList())
  }
}
