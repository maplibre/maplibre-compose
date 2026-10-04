package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.BooleanValue

/** A [Literal] representing a [Boolean] value. */
internal data class BooleanLiteral private constructor(override val value: Boolean) :
  CompiledLiteral<BooleanValue, Boolean> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  companion object {
    private val True: BooleanLiteral = BooleanLiteral(true)
    private val False: BooleanLiteral = BooleanLiteral(false)

    fun of(value: Boolean): BooleanLiteral = if (value) True else False
  }
}
