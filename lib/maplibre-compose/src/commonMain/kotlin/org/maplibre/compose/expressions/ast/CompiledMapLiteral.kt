package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.MapValue

/** A [Literal] representing a JSON object with values all [CompiledLiteral]. */
public data class CompiledMapLiteral<V : ExpressionValue>
private constructor(override val value: Map<String, CompiledLiteral<V, *>>) :
  CompiledLiteral<MapValue<V>, Map<String, Literal<V, *>>> {
  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    value.values.forEach { it.visit(block) }
  }

  public companion object {
    internal fun <T : ExpressionValue> of(
      value: Map<String, CompiledLiteral<T, *>>
    ): CompiledMapLiteral<T> = CompiledMapLiteral(value)
  }
}
