package org.maplibre.compose.util

import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.expressions.ast.BooleanLiteral
import org.maplibre.compose.expressions.ast.ColorLiteral
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.CompiledFunctionCall
import org.maplibre.compose.expressions.ast.CompiledListLiteral
import org.maplibre.compose.expressions.ast.CompiledLiteral
import org.maplibre.compose.expressions.ast.CompiledOptions
import org.maplibre.compose.expressions.ast.DpPaddingLiteral
import org.maplibre.compose.expressions.ast.FloatLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.OffsetLiteral
import org.maplibre.compose.expressions.ast.StringLiteral
import org.maplibre.compose.expressions.value.ExpressionValue

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
 * Decodes MapLibre style JSON into a compiled expression whose [toStyleJson] reproduces the input.
 * The inverse of [toStyleJson] over the JSON that MapLibre reports back, such as a layer's filter.
 */
internal fun JsonElement.toCompiledExpression(): CompiledExpression<*> = decode(inLiteral = false)

private fun JsonElement.decode(inLiteral: Boolean): CompiledExpression<*> =
  when (this) {
    is JsonNull -> NullLiteral
    is JsonPrimitive ->
      when {
        isString -> StringLiteral.of(content)
        else ->
          booleanOrNull?.let { BooleanLiteral.of(it) }
            ?: doubleOrNull?.let { FloatLiteral.of(it.toFloat()) }
            ?: StringLiteral.of(content)
      }
    is JsonObject ->
      CompiledOptions.of(
        mapValues { (_, value) -> value.decode(inLiteral).cast<ExpressionValue>() }
      )
    is JsonArray -> decodeArray(inLiteral)
  }

private fun JsonArray.decodeArray(inLiteral: Boolean): CompiledExpression<*> {
  val operator = (firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
  if (!inLiteral && operator != null) {
    // A "literal" wrapper's argument stays raw; other operators take expressions as arguments.
    val argsInLiteral = operator == "literal"
    return CompiledFunctionCall.of(
      name = operator,
      args = drop(1).map { it.decode(argsInLiteral) },
      isLiteralArg = { argsInLiteral },
    )
  }
  val elements = map { it.decode(inLiteral = true) }
  if (elements.all { it is CompiledLiteral<*, *> }) {
    return CompiledListLiteral.of(
      elements.map { (it as CompiledLiteral<*, *>).cast<ExpressionValue>() }
    )
  }
  // In a literal context a nested ["name", ...] array re-encodes byte-for-byte as a call.
  require(operator != null) { "Cannot represent the style JSON $this as an expression" }
  return CompiledFunctionCall.of(operator, drop(1).map { it.decode(inLiteral = true) }) { true }
}
