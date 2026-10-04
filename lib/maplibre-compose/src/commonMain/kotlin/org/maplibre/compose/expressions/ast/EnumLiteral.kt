package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.EnumValue
import org.maplibre.compose.expressions.value.literal

/** A [Literal] representing an enum value of type [T]. */
internal data class EnumLiteral<T : EnumValue<T>> private constructor(override val value: T) :
  Literal<T, T> {
  override fun compile(context: ExpressionContext): CompiledLiteral<T, String> =
    value.literal.cast()

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  companion object {
    fun <T : EnumValue<T>> of(value: T): EnumLiteral<T> = EnumLiteral(value)
  }
}
