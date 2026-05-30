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

internal fun CompiledExpression<*>.toJsonString(): String = normalizeJsonLike(false).toString()

private fun buildLiteralArray(
  inLiteral: Boolean,
  block: MutableList<JsonElement>.() -> Unit,
): JsonElement {
  val items = mutableListOf<JsonElement>().apply(block)
  return if (inLiteral) {
    JsonArray(items)
  } else {
    JsonArray(listOf(JsonPrimitive("literal"), JsonArray(items)))
  }
}

private fun buildLiteralObject(
  inLiteral: Boolean,
  block: MutableMap<String, JsonElement>.() -> Unit,
): JsonElement {
  val map = mutableMapOf<String, JsonElement>().apply(block)
  return if (inLiteral) {
    JsonObject(map)
  } else {
    JsonArray(listOf(JsonPrimitive("literal"), JsonObject(map)))
  }
}

private fun CompiledExpression<*>.normalizeJsonLike(inLiteral: Boolean): JsonElement =
  when (this) {
    NullLiteral -> JsonNull
    is BooleanLiteral -> JsonPrimitive(value)
    is FloatLiteral -> JsonPrimitive(value)
    is StringLiteral -> JsonPrimitive(value)
    is OffsetLiteral -> {
      val offset = value
      buildLiteralArray(inLiteral) {
        add(JsonPrimitive(offset.x))
        add(JsonPrimitive(offset.y))
      }
    }
    is ColorLiteral ->
      JsonPrimitive(
        value.toArgb().let {
          "rgba(${(it shr 16) and 0xFF}, ${(it shr 8) and 0xFF}, ${it and 0xFF}, ${value.alpha})"
        }
      )
    is DpPaddingLiteral ->
      buildLiteralArray(inLiteral) {
        add(JsonPrimitive(value.calculateTopPadding().value))
        add(JsonPrimitive(value.calculateRightPadding(LayoutDirection.Ltr).value))
        add(JsonPrimitive(value.calculateBottomPadding().value))
        add(JsonPrimitive(value.calculateLeftPadding(LayoutDirection.Ltr).value))
      }
    is CompiledFunctionCall ->
      JsonArray(
        buildList {
          add(JsonPrimitive(name))
          args.forEachIndexed { i, v -> add(v.normalizeJsonLike(inLiteral || isLiteralArg(i))) }
        }
      )
    is CompiledListLiteral<*> ->
      buildLiteralArray(inLiteral) { value.forEach { add(it.normalizeJsonLike(true)) } }
    is CompiledMapLiteral<*> ->
      buildLiteralObject(inLiteral) {
        value.forEach { (k, v) -> put(k, v.normalizeJsonLike(true)) }
      }
    is CompiledOptions<*> ->
      JsonObject(buildMap { value.forEach { (k, v) -> put(k, v.normalizeJsonLike(inLiteral)) } })
  }
