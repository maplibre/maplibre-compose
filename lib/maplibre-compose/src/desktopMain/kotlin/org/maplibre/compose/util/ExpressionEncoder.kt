package org.maplibre.compose.util

import androidx.compose.ui.unit.LayoutDirection
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
 * Serializes a [CompiledExpression] to a JSON string suitable for passing to the MapLibre style
 * API. Mirrors the Android `normalizeJsonLike` logic but uses Kotlin's stdlib instead of Gson.
 */
internal fun CompiledExpression<*>.toJsonString(): String = encodeExpression(inLiteral = false)

private fun CompiledExpression<*>.encodeExpression(inLiteral: Boolean): String =
  when (this) {
    NullLiteral -> "null"
    is BooleanLiteral -> value.toString()
    is FloatLiteral -> value.toJsonNumber()
    is StringLiteral -> jsonEscape(value)
    is ColorLiteral -> {
      val r = (value.red * 255).toInt()
      val g = (value.green * 255).toInt()
      val b = (value.blue * 255).toInt()
      val a = value.alpha
      jsonEscape("rgba($r, $g, $b, $a)")
    }
    is OffsetLiteral ->
      if (inLiteral) "[${value.x.toJsonNumber()},${value.y.toJsonNumber()}]"
      else "[\"literal\",[${value.x.toJsonNumber()},${value.y.toJsonNumber()}]]"
    is DpPaddingLiteral -> {
      val top = value.calculateTopPadding().value.toJsonNumber()
      val right = value.calculateRightPadding(LayoutDirection.Ltr).value.toJsonNumber()
      val bottom = value.calculateBottomPadding().value.toJsonNumber()
      val left = value.calculateLeftPadding(LayoutDirection.Ltr).value.toJsonNumber()
      if (inLiteral) "[$top,$right,$bottom,$left]"
      else "[\"literal\",[$top,$right,$bottom,$left]]"
    }
    is CompiledFunctionCall -> buildString {
      append('[')
      append(jsonEscape(name))
      args.forEachIndexed { i, arg ->
        append(',')
        append(arg.encodeExpression(inLiteral || isLiteralArg(i)))
      }
      append(']')
    }
    is CompiledListLiteral<*> -> {
      val items = value.joinToString(",") { it.encodeExpression(true) }
      if (inLiteral) "[$items]" else "[\"literal\",[$items]]"
    }
    is CompiledMapLiteral<*> -> {
      val entries = value.entries.joinToString(",") { (k, v) ->
        "${jsonEscape(k)}:${v.encodeExpression(true)}"
      }
      if (inLiteral) "{$entries}" else "[\"literal\",{$entries}]"
    }
    is CompiledOptions<*> ->
      value.entries.joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
        "${jsonEscape(k)}:${v.encodeExpression(inLiteral)}"
      }
  }

private fun Float.toJsonNumber(): String =
  if (isNaN() || isInfinite()) "null"
  else if (this == toLong().toFloat()) toLong().toString()
  else toString()

internal fun jsonEscape(s: String): String = buildString {
  append('"')
  for (c in s) {
    when (c) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> append(c)
    }
  }
  append('"')
}
