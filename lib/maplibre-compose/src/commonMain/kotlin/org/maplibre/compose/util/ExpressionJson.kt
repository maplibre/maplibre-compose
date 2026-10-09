package org.maplibre.compose.util

import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.BooleanLiteral
import org.maplibre.compose.expressions.ast.ColorLiteral
import org.maplibre.compose.expressions.ast.CompiledCallArgument
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.CompiledFunctionCall
import org.maplibre.compose.expressions.ast.CompiledListLiteral
import org.maplibre.compose.expressions.ast.CompiledOptions
import org.maplibre.compose.expressions.ast.CompiledSemiliteral
import org.maplibre.compose.expressions.ast.DpPaddingLiteral
import org.maplibre.compose.expressions.ast.FloatLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.OffsetLiteral
import org.maplibre.compose.expressions.ast.ProjectionTransitionLiteral
import org.maplibre.compose.expressions.ast.StringLiteral
import org.maplibre.compose.expressions.ast.Verbatim

/** Encodes a compiled expression as MapLibre style JSON. */
internal fun CompiledExpression<*>.toStyleJson(): JsonElement = normalizeJsonLike(inLiteral = false)

/**
 * @param inLiteral whether this node is already inside a `["literal", ...]` wrapper. Arrays and
 *   objects need literal context because the style spec reads `[1, 2]` as a function call.
 */
@OptIn(ExperimentalMaplibreComposeApi::class)
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

    // Never wrapped: the spec reads a bare `[from, to, progress]` as a projection transition
    // state, while a `literal` array is typed as an array and fails projection validation.
    is ProjectionTransitionLiteral ->
      JsonArray(
        listOf(
          JsonPrimitive(value.from.value),
          JsonPrimitive(value.to.value),
          JsonPrimitive(value.progress),
        )
      )

    is DpPaddingLiteral ->
      // Style order is top, right, bottom, left.
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
      JsonArray(listOf(JsonPrimitive(name)) + args.map { it.toArgumentJson() })

    is CompiledSemiliteral<*> -> {
      val array =
        JsonArray(
          listOf(
            JsonPrimitive("semiliteral"),
            JsonArray(elements.map { it.normalizeJsonLike(inLiteral = false) }),
          )
        )
      // style-spec 26.4.2 parses semiliteral children in the parent's expected array type and
      // crashes. A let binding gives the array an unconstrained context while preserving its
      // inferred type. Remove the binding when the pinned GL JS parser handles children itself.
      // https://github.com/maplibre/maplibre-style-spec/blob/v26.4.2/src/expression/definitions/semiliteral.ts
      JsonArray(
        listOf(
          JsonPrimitive("let"),
          JsonPrimitive("semiliteral_value"),
          array,
          JsonArray(listOf(JsonPrimitive("var"), JsonPrimitive("semiliteral_value"))),
        )
      )
    }

    is CompiledListLiteral<*> ->
      literalArray(inLiteral, value.map { it.normalizeJsonLike(inLiteral = true) })
  }

private fun CompiledCallArgument.toArgumentJson(): JsonElement =
  when (this) {
    is CompiledExpression<*> -> toStyleJson()
    is Verbatim -> json
    is CompiledOptions -> JsonObject(entries.mapValues { it.value.toStyleJson() })
  }

private fun literalArray(inLiteral: Boolean, values: List<JsonElement>): JsonElement =
  if (inLiteral) JsonArray(values)
  else JsonArray(listOf(JsonPrimitive("literal"), JsonArray(values)))
