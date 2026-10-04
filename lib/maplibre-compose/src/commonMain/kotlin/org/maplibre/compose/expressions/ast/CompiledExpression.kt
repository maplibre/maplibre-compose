package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** An [Expression] reduced to the data types supported by MapLibre. */
internal sealed interface CompiledExpression<out T : ExpressionValue?> :
  ExpressionNode<T>, CompiledCallArgument {
  override fun compile(context: ExpressionContext): CompiledExpression<T> = this

  @Suppress("UNCHECKED_CAST")
  override fun <X : ExpressionValue?> cast(): CompiledExpression<X> = this as CompiledExpression<X>
}
