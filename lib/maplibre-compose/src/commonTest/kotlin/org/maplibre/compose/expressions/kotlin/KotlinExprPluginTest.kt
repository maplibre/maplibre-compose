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
import org.maplibre.compose.expressions.value.FormattedValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

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
  fun kotlin_when_on_a_subject_becomes_match() {
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
    assertTrue(encoded.startsWith("[\"match\","), encoded)
    assertTrue(encoded.contains("park"), encoded)
  }

  @Test
  fun kotlin_when_without_a_subject_stays_case() {
    val encoded =
      json(
        expr<ColorValue> {
          val mag = feature.number("mag")
          when {
            mag >= 6 -> Color.Red
            mag >= 4 -> Color.Yellow
            else -> Color.Gray
          }
        }
      )
    assertTrue(encoded.startsWith("[\"case\","), encoded)
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

  @Test
  fun match_helper_and_nil() {
    val encoded =
      json(
        expr<ColorValue> {
          match(
            feature.string("kind"),
            "park" to Color.Green,
            listOf("road", "path") to Color.Gray,
            fallback = Color.Red,
          )
        }
      )
    assertTrue(encoded.startsWith("[\"match\","), encoded)
    assertEquals("null", json(expr<ExpressionValue> { nil() }))
  }

  @Test
  fun as_enum_and_collection_asserts() {
    val encoded =
      json(
        expr<StringValue> {
          feature["kind"].asEnum(listOf("park", "water"), "park")
        }
      )
    assertTrue(encoded.contains("\"in\""), encoded)
    assertTrue(encoded.contains("\"case\""), encoded)

    val offset = json(expr<ExpressionValue> { feature["offset"].asOffset() })
    assertTrue(offset.contains("\"array\""), offset)
    assertTrue(offset.contains("2"), offset)

    val padding = json(expr<ExpressionValue> { feature["pad"].asPadding() })
    assertTrue(padding.contains("4"), padding)
  }

  @Test
  fun map_get_and_has_after_as_map() {
    val encoded =
      json(
        expr<BooleanValue> {
          feature.properties().has("name") && feature.properties()["kind"] != null
        }
      )
    assertTrue(encoded.contains("\"has\""), encoded)
    assertTrue(encoded.contains("\"get\""), encoded)
    assertTrue(encoded.contains("\"properties\""), encoded)
  }

  @Test
  fun unit_conversions_and_map_constants() {
    val seconds = json(expr<ExpressionValue> { feature.number("delay").seconds })
    assertTrue(seconds.contains("1000") || seconds.contains("*"), seconds)

    val sp = expr<ExpressionValue> { feature.number("size").sp }
    assertTrue(sp is org.maplibre.compose.expressions.ast.TextUnitCalculation)

    val constants = json(expr<FloatValue> { ln2 + pi + e })
    assertTrue(constants.contains("\"ln2\""), constants)
    assertTrue(constants.contains("\"pi\""), constants)
    assertTrue(constants.contains("\"e\""), constants)
  }

  @Test
  fun collator_compare_and_resolved_locale() {
    val encoded =
      json(
        expr<BooleanValue> {
          val locale = collator(locale = "en")
          eq(feature.string("name"), "Park", locale) && resolvedLocale(locale).isScriptSupported()
        }
      )
    assertTrue(encoded.contains("\"collator\"") || encoded.contains("=="), encoded)
  }

  @Test
  fun format_keeps_span_options() {
    val encoded =
      json(
        expr<FormattedValue> {
          format(span(feature.string("name"), font = listOf("Open Sans"), textColor = Color.Red))
        }
      )
    assertTrue(encoded.startsWith("[\"format\","), encoded)
    assertTrue(encoded.contains("text-font") || encoded.contains("text-color"), encoded)
  }

  @Test
  fun image_name_is_not_double_wrapped() {
    val encoded = json(expr<ExpressionValue> { image("marker") })
    assertEquals("""["image","marker"]""", encoded)
  }

  @Test
  fun within_uses_a_geojson_literal() {
    val ring = Point(Position(longitude = 0.0, latitude = 0.0))
    val encoded = json(expr<BooleanValue> { feature.within(ring) })
    assertTrue(encoded.startsWith("[\"within\","), encoded)
    assertTrue(encoded.contains("Point") || encoded.contains("point"), encoded)
  }

  @Test
  fun string_script_and_type_of() {
    val encoded =
      json(expr<StringValue> { feature.string("name").isScriptSupported().convertToString() })
    assertTrue(encoded.contains("is-supported-script") || encoded.contains("to-string"), encoded)
    val type = json(expr<StringValue> { feature["mag"].typeOf() })
    assertTrue(type.contains("typeof"), type)
  }

  @Test
  fun compose_function_with_constable_result_is_frozen() {
    fun themeRed(): Color = Color.Red
    val encoded = json(expr<ColorValue> { themeRed() })
    assertTrue(encoded.contains("rgba("), encoded)
  }
}
