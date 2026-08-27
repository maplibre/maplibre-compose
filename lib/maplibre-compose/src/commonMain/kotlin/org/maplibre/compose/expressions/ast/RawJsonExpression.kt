package org.maplibre.compose.expressions.ast

import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.expressions.value.ExpressionValue

/**
 * A style-JSON expression held verbatim, as MapLibre reported it. Re-encoding a value of this type
 * reproduces [json] exactly, so an expression read back from the live style survives a round trip
 * with full fidelity.
 */
public data class RawJsonExpression(public val json: JsonElement) :
  CompiledExpression<ExpressionValue> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)
}
