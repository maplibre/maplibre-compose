package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.StringValue

/** A [Literal] representing a [String] value. */
public data class StringLiteral private constructor(override val value: String) :
  CompiledLiteral<StringValue, String> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    private val empty = StringLiteral("")

    public fun of(value: String): StringLiteral =
      if (value.isEmpty()) empty else StringLiteral(value)
  }
}
