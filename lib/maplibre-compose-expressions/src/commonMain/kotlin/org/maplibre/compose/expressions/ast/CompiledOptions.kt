package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.MapValue

/** An [Expression] representing a JSON object with values all [CompiledExpression]. */
public data class CompiledOptions<T : ExpressionValue>
private constructor(val value: Map<String, CompiledExpression<T>>) :
  CompiledExpression<MapValue<T>> {
  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    value.values.forEach { it.visit(block) }
  }

  public companion object {
    internal fun <T : ExpressionValue> of(
      value: Map<String, CompiledExpression<T>>
    ): CompiledOptions<T> = CompiledOptions(value)
  }
}
