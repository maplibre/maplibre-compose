package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/** The library's implementation of [Expression], which every expression is. */
internal sealed interface ExpressionNode<out T : ExpressionValue?> : Expression<T> {
  /** Transform this expression into the equivalent [CompiledExpression]. */
  fun compile(context: ExpressionContext): CompiledExpression<T>

  fun visit(block: (Expression<*>) -> Unit)
}

/** Transform this expression into the equivalent [CompiledExpression]. */
internal fun <T : ExpressionValue?> Expression<T>.compile(
  context: ExpressionContext
): CompiledExpression<T> = (this as ExpressionNode<T>).compile(context)

internal fun Expression<*>.visit(block: (Expression<*>) -> Unit) =
  (this as ExpressionNode<*>).visit(block)
