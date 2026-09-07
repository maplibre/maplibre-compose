package org.maplibre.compose.expressions.kotlin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.expressions.ast.CompiledFunctionCall
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.util.toStyleJson

class KotlinExprPluginTest {
  private fun json(expression: Expression<*>): String =
    expression.compile(ExpressionContext.None).toStyleJson().toString()

  private fun call(expression: Expression<*>): CompiledFunctionCall =
    expression.compile(ExpressionContext.None) as CompiledFunctionCall

  @Test
  fun constants_become_literals() {
    assertEquals("true", json(expr<BooleanValue> { true }))
    assertEquals("1.5", json(expr<FloatValue> { 1.5 }))
    assertEquals("\"park\"", json(expr<StringValue> { "park" }))
    assertTrue(json(expr<ColorValue> { Color.Red }).contains("rgba("))
    assertTrue(json(expr<DpValue> { 4.dp }).contains("4"))
  }

  @Test
  fun arithmetic_uses_maplibre_operators() {
    val sum = call(expr<IntValue> { 1 + 2 })
    assertEquals("+", sum.name)
  }

  @Test
  fun feature_property_and_comparison() {
    val encoded = json(expr<BooleanValue> { feature.number("mag") > 5 })
    assertTrue(encoded.contains("\"get\""), encoded)
    assertTrue(encoded.contains("\"mag\""), encoded)
  }

  @Test
  fun kotlin_if_becomes_case() {
    val encoded =
      json(
        expr<ColorValue> {
          if (feature.number("mag") > 5) {
            Color.Red
          } else {
            Color.Yellow
          }
        }
      )
    assertTrue(encoded.startsWith("[\"case\","), encoded)
    assertTrue(encoded.contains("\"get\""), encoded)
  }

  @Test
  fun kotlin_when_becomes_case() {
    val encoded =
      json(
        expr<ColorValue> {
          when (feature.string("kind")) {
            "park" -> Color.Green
            "water" -> Color.Blue
            else -> Color.Gray
          }
        }
      )
    assertTrue(encoded.startsWith("[\"case\","), encoded)
    assertTrue(encoded.contains("park"), encoded)
  }

  @Test
  fun boolean_and_or_not() {
    val encoded = json(expr<BooleanValue> { feature.has("mag") && !feature.has("place") })
    assertTrue(encoded.contains("\"all\"") || encoded.contains("\"case\""), encoded)
    assertTrue(encoded.contains("\"has\""), encoded)
  }

  @Test
  fun interpolate_and_zoom() {
    val encoded = json(expr<FloatValue> { interpolate(exponential(2), zoom, 5 to 2.0, 10 to 8.0) })
    assertTrue(encoded.startsWith("[\"interpolate\","), encoded)
    assertTrue(encoded.contains("\"exponential\""), encoded)
    assertTrue(encoded.contains("\"zoom\""), encoded)
  }

  @Test
  fun step_on_feature() {
    val encoded = json(expr<FloatValue> { step(feature.number("mag"), 4.0, 4 to 8.0, 6 to 16.0) })
    assertTrue(encoded.startsWith("[\"step\","), encoded)
  }

  @Test
  fun string_methods_and_concat() {
    val encoded = json(expr<StringValue> { feature.string("name").uppercase() + " quake" })
    assertTrue(encoded.contains("\"upcase\"") || encoded.contains("\"concat\""), encoded)
  }

  @Test
  fun kotlin_math() {
    val encoded = json(expr<FloatValue> { sqrt(feature.number("mag")) })
    assertTrue(encoded.contains("\"sqrt\""), encoded)
  }

  @Test
  fun captured_outer_value() {
    val threshold = 5.0
    val encoded = json(expr<BooleanValue> { feature.number("mag") > threshold })
    assertTrue(encoded.contains("5"), encoded)
  }

  @Test
  fun local_val_is_inlined() {
    val encoded =
      json(
        expr<FloatValue> {
          val mag = feature.number("mag")
          mag * 2
        }
      )
    assertTrue(encoded.contains("\"*\""), encoded)
    assertTrue(encoded.contains("\"get\""), encoded)
  }

  @Test
  fun filter_style_boolean() {
    val encoded = json(expr<BooleanValue> { feature.number("mag") > 5 && feature.has("place") })
    assertTrue(encoded.contains("\"get\"") || encoded.contains("\"has\""), encoded)
  }

  @Test
  fun emit_api_matches_plugin_for_literals() {
    assertEquals(json(ExprEmit.lit(3)), json(expr<IntValue> { 3 }))
  }

  @Test
  fun unused_expression_value_bound() {
    val unused: Expression<ExpressionValue> = expr { "ok" }
    assertEquals("\"ok\"", json(unused))
  }
}
