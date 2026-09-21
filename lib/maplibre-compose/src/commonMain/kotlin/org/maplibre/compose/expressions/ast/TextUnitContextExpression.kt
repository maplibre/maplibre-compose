package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue

/** Captures text-unit conversion without resolving image references. */
internal data class TextUnitContextExpression<T : ExpressionValue?>(
  val expression: Expression<T>,
  val emScale: Expression<FloatValue>,
  val spScale: Expression<FloatValue>,
) : Expression<T> {
  override fun compile(context: ExpressionContext): CompiledExpression<T> =
    expression.compile(
      object : ExpressionContext by context {
        override val emScale = this@TextUnitContextExpression.emScale
        override val spScale = this@TextUnitContextExpression.spScale
      }
    )

  override fun visit(block: (Expression<*>) -> Unit) {
    expression.visit(block)
    emScale.visit(block)
    spScale.visit(block)
  }
}
