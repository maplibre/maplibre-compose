package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.EnumValue

/** A [Literal] representing an enum value of type [T]. */
public data class EnumLiteral<T : EnumValue<T>> private constructor(override val value: T) :
  Literal<T, T> {
  override fun compile(context: ExpressionContext): CompiledLiteral<T, String> =
    value.literal.cast()

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    internal fun <T : EnumValue<T>> of(value: T): EnumLiteral<T> = EnumLiteral(value)
  }
}
