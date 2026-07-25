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
import org.maplibre.compose.expressions.ast.CompiledMapLiteral
import org.maplibre.compose.expressions.ast.CompiledOptions
import org.maplibre.compose.expressions.ast.DpPaddingLiteral
import org.maplibre.compose.expressions.ast.FloatLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.OffsetLiteral
import org.maplibre.compose.expressions.ast.StringLiteral

/**
 * Encodes a compiled expression as MapLibre style JSON.
 *
 * This mirrors the Android encoder exactly, including its treatment of `literal` wrapping and its
 * colour format. The encodings are part of the MapLibre style spec rather than of any one platform,
 * so the two must agree: a desktop map given the same expression as an Android map has to render
 * identically.
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

    is CompiledMapLiteral<*> ->
      literalObject(inLiteral, value.mapValues { (_, v) -> v.normalizeJsonLike(inLiteral = true) })

    // Options are always a plain object: they are named arguments to a function call, never a
    // value the style spec could mistake for an expression.
    is CompiledOptions<*> ->
      JsonObject(value.mapValues { (_, v) -> v.normalizeJsonLike(inLiteral) })
  }

private fun literalArray(inLiteral: Boolean, values: List<JsonElement>): JsonElement =
  if (inLiteral) JsonArray(values)
  else JsonArray(listOf(JsonPrimitive("literal"), JsonArray(values)))

/**
 * Wraps a map literal.
 *
 * This deliberately differs from Android and iOS, which both emit `{"literal": {...}}` here. Those
 * hand the result to the platform SDK's own expression parser; desktop writes raw style JSON, where
 * the style spec defines `literal` as an operator and therefore requires the array form. The object
 * form would parse as an ordinary object with a `literal` key.
 *
 * TODO(maplibre-compose): confirm against a running map once a style with a map literal is
 *   exercised, and reconcile with Android/iOS if they turn out to be wrong rather than merely
 *   different.
 */
private fun literalObject(inLiteral: Boolean, values: Map<String, JsonElement>): JsonElement =
  if (inLiteral) JsonObject(values)
  else JsonArray(listOf(JsonPrimitive("literal"), JsonObject(values)))
