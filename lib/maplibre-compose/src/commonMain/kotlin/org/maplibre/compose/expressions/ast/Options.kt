package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.MapValue

/** An [Expression] representing a JSON object with values all [Expression]. */
internal data class Options<T : ExpressionValue?>
private constructor(val value: Map<String, Expression<T>>) : ExpressionNode<MapValue<T>> {

  override fun compile(context: ExpressionContext): CompiledOptions<T> =
    CompiledOptions.of(value.mapValues { it.value.compile(context) })

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    value.values.forEach { it.visit(block) }
  }

  companion object {
    fun build(block: MutableMap<String, Expression<*>>.() -> Unit) = Options(buildMap(block))
  }
}
