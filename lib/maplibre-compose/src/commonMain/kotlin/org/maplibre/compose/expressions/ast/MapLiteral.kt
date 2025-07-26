package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.MapValue

/** A [Literal] representing a JSON object. */
public data class MapLiteral<T : ExpressionValue>
private constructor(override val value: Map<String, Literal<T, *>>) :
  Literal<MapValue<T>, Map<String, Literal<T, *>>> {

  override fun compile(context: ExpressionContext): CompiledMapLiteral<T> {
    return CompiledMapLiteral.of(value.mapValues { it.value.compile(context) })
  }

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    value.values.forEach { it.visit(block) }
  }

  public companion object {
    internal fun <T : ExpressionValue> of(value: Map<String, Literal<T, *>>): MapLiteral<T> =
      MapLiteral(value)
  }
}
