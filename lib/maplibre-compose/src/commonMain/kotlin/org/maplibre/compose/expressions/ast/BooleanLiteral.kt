package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.BooleanValue

/** A [Literal] representing a [Boolean] value. */
public data class BooleanLiteral private constructor(override val value: Boolean) :
  CompiledLiteral<BooleanValue, Boolean> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    // Do not intern True/False. On Kotlin/JS the companion initializes those
    // instances by constructing BooleanLiteral, which re-enters the companion
    // before the fields are assigned, so of(true) can return undefined.
    public fun of(value: Boolean): BooleanLiteral = BooleanLiteral(value)
  }
}
