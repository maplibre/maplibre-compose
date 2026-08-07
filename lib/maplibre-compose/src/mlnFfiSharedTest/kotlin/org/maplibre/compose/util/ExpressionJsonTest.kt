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

class ExpressionJsonTest {

  private fun json(expression: CompiledExpression<*>): String = expression.toStyleJson().toString()

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
    assertEquals("\"rgba(255, 0, 0, 1.0)\"", json(ColorLiteral.of(Color.Red)))
    assertEquals("\"rgba(0, 255, 0, 0.0)\"", json(ColorLiteral.of(Color.Green.copy(alpha = 0f))))

    // Compose quantizes alpha to 8 bits, so a nominal 0.5 round-trips as 128/255.
    assertEquals(
      "\"rgba(0, 0, 255, 0.5019608)\"",
      json(ColorLiteral.of(Color.Blue.copy(alpha = 0.5f))),
    )
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
    // `[1, 2]` on its own would parse as a call to the operator named "1".
    assertEquals("""["literal",[1.0,2.0]]""", json(OffsetLiteral.of(Offset(1f, 2f))))
  }

  @Test
  fun does_not_double_wrap_an_array_already_inside_a_literal() {
    // isLiteralArg marks argument positions already in literal context; wrapping again would
    // produce ["literal", ["literal", ...]] and change the value.
    val expression =
      CompiledFunctionCall.of(
        "literal",
        listOf(OffsetLiteral.of(Offset(1f, 2f)).compile(ExpressionContext.None)),
        isLiteralArg = { true },
      )
    assertEquals("""["literal",[1.0,2.0]]""", json(expression))
  }

  @Test
  fun encodes_padding_in_the_style_spec_s_top_right_bottom_left_order() {
    // PaddingValues reads start/top/end/bottom, so the order has to be rebuilt rather than copied.
    val padding =
      DpPaddingLiteral.of(
        PaddingValues.Absolute(left = 4.dp, top = 1.dp, right = 2.dp, bottom = 3.dp)
      )
    assertEquals("""["literal",[1.0,2.0,3.0,4.0]]""", json(padding))
  }
}
