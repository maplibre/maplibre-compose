package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.NullValue

/** A [Literal] representing a `null` value. */
public data object NullLiteral : CompiledLiteral<NullValue, Nothing?> {
  override val value: Nothing? = null

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)
}
