package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.ListValue

/** A [Literal] representing a JSON array with elements all [CompiledLiteral]. */
internal data class CompiledListLiteral<T : ExpressionValue?>
private constructor(override val value: List<CompiledLiteral<T, *>>) :
  CompiledLiteral<ListValue<T>, List<Literal<T, *>>> {
  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    value.forEach { it.visit(block) }
  }

  companion object {
    fun <T : ExpressionValue?> of(value: List<CompiledLiteral<T, *>>): CompiledListLiteral<T> =
      CompiledListLiteral(value)
  }
}
