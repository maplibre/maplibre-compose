package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.BooleanLiteral
import org.maplibre.compose.expressions.ast.ColorLiteral
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.CompiledFunctionCall
import org.maplibre.compose.expressions.ast.DpPaddingLiteral
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.FloatLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.OffsetLiteral
import org.maplibre.compose.expressions.ast.StringLiteral
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.padding

/**
 * Written against values, not rendered text, wherever a whole number is involved: Kotlin renders
 * `1f` as `1.0` on the JVM and `1` in JavaScript, and both are the same JSON number.
 */
class ExpressionJsonTest {

  private fun json(expression: CompiledExpression<*>): String = expression.toStyleJson().toString()

  private fun rgba(color: Color): List<Double> =
    json(ColorLiteral.of(color))
      .removeSurrounding("\"")
      .removeSurrounding("rgba(", ")")
      .split(", ")
      .map { it.toDouble() }

  private fun compiled(literal: org.maplibre.compose.expressions.ast.Expression<*>) =
    literal.compile(ExpressionContext.None)

  @Test
  fun encodes_scalars_as_bare_json_values() {
    assertEquals("null", json(NullLiteral))
    assertEquals("true", json(BooleanLiteral.of(true)))
    assertEquals("1.5", json(FloatLiteral.of(1.5f)))
    assertEquals("\"park\"", json(StringLiteral.of("park")))
  }

  @Test
  fun encodes_a_colour_in_the_style_spec_s_rgba_form() {
    // The style spec takes colours as CSS strings: channels are 0-255 and alpha stays fractional.
    assertEquals(listOf(255.0, 0.0, 0.0, 1.0), rgba(Color.Red))
    assertEquals(listOf(0.0, 255.0, 0.0, 0.0), rgba(Color.Green.copy(alpha = 0f)))

    // Compose quantizes alpha to 8 bits, so a nominal 0.5 round-trips as 128/255.
    val translucent = rgba(Color.Blue.copy(alpha = 0.5f))
    assertEquals(listOf(0.0, 0.0, 255.0), translucent.take(3))
    assertEquals(128.0 / 255.0, translucent[3], 1e-6)
  }

  @Test
  fun encodes_a_function_call_as_an_operator_array() {
    val expression =
      CompiledFunctionCall.of(
        "==",
        listOf(
          CompiledFunctionCall.of("get", listOf(StringLiteral.of("class"))),
          StringLiteral.of("park"),
        ),
      )
    assertEquals("""["==",["get","class"],"park"]""", json(expression))
  }

  @Test
  fun wraps_a_bare_array_in_literal_so_it_is_not_read_as_an_operator() {
    // `[1.5, 2.5]` on its own would parse as a call to the operator named "1.5".
    assertEquals("""["literal",[1.5,2.5]]""", json(OffsetLiteral.of(Offset(1.5f, 2.5f))))
  }

  @Test
  fun does_not_double_wrap_an_array_already_inside_a_literal() {
    // isLiteralArg marks argument positions already in literal context.
    val expression =
      CompiledFunctionCall.of(
        "literal",
        listOf(OffsetLiteral.of(Offset(1.5f, 2.5f)).compile(ExpressionContext.None)),
        isLiteralArg = { true },
      )
    assertEquals("""["literal",[1.5,2.5]]""", json(expression))
  }

  @Test
  fun encodes_padding_in_the_style_spec_s_top_right_bottom_left_order() {
    val padding =
      DpPaddingLiteral.of(DpPadding(left = 4.5.dp, top = 1.5.dp, right = 2.5.dp, bottom = 3.5.dp))
    assertEquals("""["literal",[1.5,2.5,3.5,4.5]]""", json(padding))
  }

  @Test
  fun encodes_negative_padding_sides() {
    val padding = padding(left = 2.5.dp, top = (-2.5).dp, right = 0.dp, bottom = (-7).dp)
    assertEquals("""["literal",[-2.5,0.0,-7.0,2.5]]""", json(compiled(padding)))
  }

  @Test
  fun copies_compose_absolute_padding_into_a_literal() {
    val padding =
      const(PaddingValues.Absolute(left = 4.5.dp, top = 1.5.dp, right = 2.5.dp, bottom = 3.5.dp))
    assertEquals("""["literal",[1.5,2.5,3.5,4.5]]""", json(padding))
  }
}
