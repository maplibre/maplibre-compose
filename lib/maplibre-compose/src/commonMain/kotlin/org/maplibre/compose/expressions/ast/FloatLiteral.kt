package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.FloatValue

/** A [Literal] representing a [Number] value. */
internal data class FloatLiteral private constructor(override val value: Float) :
  CompiledLiteral<FloatValue, Float> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  companion object {
    private val cache = FloatCache(::FloatLiteral)

    fun of(value: Float): FloatLiteral = cache[value]
  }
}
