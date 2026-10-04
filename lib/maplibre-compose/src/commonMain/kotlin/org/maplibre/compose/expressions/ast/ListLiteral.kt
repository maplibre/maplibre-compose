package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.ListValue

/** A [Literal] representing a JSON array. */
internal data class ListLiteral<T : ExpressionValue?>
private constructor(override val value: List<Literal<T, *>>) :
  Literal<ListValue<T>, List<Literal<T, *>>> {

  override fun compile(context: ExpressionContext): CompiledListLiteral<T> =
    CompiledListLiteral.of(value.map { it.compile(context) })

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    value.forEach { it.visit(block) }
  }

  companion object {
    fun <T : ExpressionValue?> of(value: List<Literal<T, *>>): ListLiteral<T> = ListLiteral(value)
  }
}
