package org.maplibre.compose.expressions.ast

/** A [Literal] representing a `null` value. */
public data object NullLiteral : CompiledLiteral<Nothing, Nothing?> {
  override val value: Nothing? = null

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)
}
