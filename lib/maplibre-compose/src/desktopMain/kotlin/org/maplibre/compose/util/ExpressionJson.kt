package org.maplibre.compose.util

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.LayoutDirection
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
import org.maplibre.compose.expressions.ast.StringLiteral

/**
 * Encodes a compiled expression as MapLibre style JSON. Must stay identical to the Android encoder,
 * including `literal` wrapping and colour format; these are style-spec encodings, not platform
 * ones.
 */
internal fun CompiledExpression<*>.toStyleJson(): JsonElement = normalizeJsonLike(inLiteral = false)

/**
 * @param inLiteral whether this node is already inside a `["literal", ...]` wrapper. Arrays and
 *   objects are ambiguous in the style spec — `[1, 2]` reads as a function call — so they are
 *   wrapped unless something above already did it.
 */
private fun CompiledExpression<*>.normalizeJsonLike(inLiteral: Boolean): JsonElement =
  when (this) {
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
      // Style order is top, right, bottom, left — not the order PaddingValues reads in.
      literalArray(
        inLiteral,
        listOf(
          JsonPrimitive(value.calculateTopPadding().value),
          JsonPrimitive(value.calculateRightPadding(LayoutDirection.Ltr).value),
          JsonPrimitive(value.calculateBottomPadding().value),
          JsonPrimitive(value.calculateLeftPadding(LayoutDirection.Ltr).value),
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

    // Named arguments to a function call, never a value the style spec could read as an expression.
    is CompiledOptions<*> ->
      JsonObject(value.mapValues { (_, v) -> v.normalizeJsonLike(inLiteral) })
  }

private fun literalArray(inLiteral: Boolean, values: List<JsonElement>): JsonElement =
  if (inLiteral) JsonArray(values)
  else JsonArray(listOf(JsonPrimitive("literal"), JsonArray(values)))
