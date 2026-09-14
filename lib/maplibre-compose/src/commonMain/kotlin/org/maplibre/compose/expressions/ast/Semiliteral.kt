package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.ListValue

/** An array whose elements are evaluated as expressions. */
internal data class Semiliteral<T : ExpressionValue?>(val elements: List<Expression<T>>) :
  Expression<ListValue<T>> {
  override fun compile(context: ExpressionContext): CompiledExpression<ListValue<T>> =
    CompiledSemiliteral(elements.map { it.compile(context) })

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    elements.forEach { it.visit(block) }
  }
}

internal data class CompiledSemiliteral<T : ExpressionValue?>(
  val elements: List<CompiledExpression<T>>
) : CompiledExpression<ListValue<T>> {
  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    elements.forEach { it.visit(block) }
  }
}
