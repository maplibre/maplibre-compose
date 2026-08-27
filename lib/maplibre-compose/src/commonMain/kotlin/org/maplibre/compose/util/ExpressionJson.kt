package org.maplibre.compose.util

import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.BooleanLiteral
import org.maplibre.compose.expressions.ast.ColorLiteral
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.CompiledFunctionCall
import org.maplibre.compose.expressions.ast.CompiledListLiteral
import org.maplibre.compose.expressions.ast.CompiledOptions
import org.maplibre.compose.expressions.ast.DpPaddingLiteral
import org.maplibre.compose.expressions.ast.FloatLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.OffsetLiteral
import org.maplibre.compose.expressions.ast.RawJsonExpression
import org.maplibre.compose.expressions.ast.StringLiteral

/**
 * Encodes a compiled expression as MapLibre style JSON. Must stay identical to the Android encoder,
 * including `literal` wrapping and colour format.
 */
internal fun CompiledExpression<*>.toStyleJson(): JsonElement = normalizeJsonLike(inLiteral = false)

/**
 * @param inLiteral whether this node is already inside a `["literal", ...]` wrapper. Arrays and
 *   objects are ambiguous in the style spec — `[1, 2]` reads as a function call — so they are
 *   wrapped unless something above already did it.
 */
private fun CompiledExpression<*>.normalizeJsonLike(inLiteral: Boolean): JsonElement =
  when (this) {
    is RawJsonExpression -> json

    NullLiteral -> JsonNull
    is BooleanLiteral -> JsonPrimitive(value)
    is FloatLiteral -> JsonPrimitive(value)
    is StringLiteral -> JsonPrimitive(value)

    is OffsetLiteral ->
      literalArray(inLiteral, listOf(JsonPrimitive(value.x), JsonPrimitive(value.y)))

    is ColorLiteral ->
      JsonPrimitive(
        value.toArgb().let {
          "rgba(${(it shr 16) and 0xFF}, ${(it shr 8) and 0xFF}, ${it and 0xFF}, ${value.alpha})"
        }
      )

    is DpPaddingLiteral ->
      // Style order is top, right, bottom, left — not the order DpPadding stores.
      literalArray(
        inLiteral,
        listOf(
          JsonPrimitive(value.top.value),
          JsonPrimitive(value.right.value),
          JsonPrimitive(value.bottom.value),
          JsonPrimitive(value.left.value),
        ),
      )

    is CompiledFunctionCall ->
      JsonArray(
        buildList {
          add(JsonPrimitive(name))
          args.forEachIndexed { index, arg ->
            add(arg.normalizeJsonLike(inLiteral || isLiteralArg(index)))
          }
        }
      )

    is CompiledListLiteral<*> ->
      literalArray(inLiteral, value.map { it.normalizeJsonLike(inLiteral = true) })

    // Not wrapped: an object is a function call's named arguments, never a readable expression.
    is CompiledOptions<*> ->
      JsonObject(value.mapValues { (_, v) -> v.normalizeJsonLike(inLiteral) })
  }

private fun literalArray(inLiteral: Boolean, values: List<JsonElement>): JsonElement =
  if (inLiteral) JsonArray(values)
  else JsonArray(listOf(JsonPrimitive("literal"), JsonArray(values)))

/**
 * Wraps MapLibre style JSON as a compiled expression whose [toStyleJson] reproduces the input
 * verbatim. The inverse of [toStyleJson] over the JSON that MapLibre reports back, such as a
 * layer's filter.
 */
internal fun JsonElement.toCompiledExpression(): CompiledExpression<*> = RawJsonExpression(this)
