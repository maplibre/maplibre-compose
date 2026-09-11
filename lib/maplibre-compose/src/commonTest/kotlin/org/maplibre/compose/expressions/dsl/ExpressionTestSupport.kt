package org.maplibre.compose.expressions.dsl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.FontLiteral
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.util.toStyleJson

/**
 * Compiles [expression] and renders it as style JSON with platform-independent number formatting.
 *
 * Kotlin prints a whole float as `1.0` on the JVM and `1` in JavaScript, and a colour string
 * carries the same difference in its alpha channel. Both are the same JSON to MapLibre, so tests
 * compare against the integer form.
 */
internal fun styleJson(
  expression: Expression<*>,
  context: ExpressionContext = ExpressionContext.None,
): String = expression.compile(context).toStyleJson().canonical()

private fun JsonElement.canonical(): String =
  when (this) {
    JsonNull -> "null"
    is JsonPrimitive ->
      if (isString)
        JsonPrimitive(content.replace(Regex("""^(rgba\(.*), 1\.0\)$"""), "$1, 1)")).toString()
      else content.toDoubleOrNull()?.let { canonicalNumber(it) } ?: content
    is JsonArray -> joinToString(",", "[", "]") { it.canonical() }
    is JsonObject ->
      entries.joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${v.canonical()}" }
  }

private fun canonicalNumber(value: Double): String =
  if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** A compilation context that scales text units by fixed factors: 16 per em and 1.5 per sp. */
internal object TextContext : ExpressionContext {
  override val emScale: Expression<FloatValue> = const(16f)
  override val spScale: Expression<FloatValue> = const(1.5f)

  override fun resolveBitmap(bitmap: BitmapLiteral): String = error("no bitmaps in tests")

  override fun resolvePainter(painter: PainterLiteral): String = error("no painters in tests")

  override fun resolveFont(font: FontLiteral): List<String> = error("no fonts in tests")
}
