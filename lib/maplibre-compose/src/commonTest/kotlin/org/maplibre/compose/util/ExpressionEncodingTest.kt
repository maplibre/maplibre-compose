package org.maplibre.compose.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledListLiteral
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.StringLiteral
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.call
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.list
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.dsl.options
import org.maplibre.compose.expressions.dsl.padding
import org.maplibre.compose.expressions.dsl.verbatim
import org.maplibre.compose.expressions.value.ProjectionType
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.style.ProjectionTransition
import org.maplibre.compose.style.internal.StyleValue

class ExpressionEncodingTest {
  @Test
  fun engine_transports_preserve_the_style_spec_forms() {
    val expressions =
      listOf<Expression<*>>(
        nil(),
        const(true),
        const(1.25f),
        const("park"),
        const(Offset(1.25f, -2.5f)),
        const(Color.Blue.copy(alpha = 0.5f)),
        padding(1.dp, 2.dp, 3.dp, 4.dp),
        CompiledListLiteral.of(
          listOf(CompiledListLiteral.of(listOf(StringLiteral.of("get"), StringLiteral.of("key"))))
        ),
        list(feature["x"].asNumber(), const(2f)),
        const(
          ProjectionTransition(ProjectionType.Mercator, ProjectionType.VerticalPerspective, 0.5f)
        ),
        call<StringValue>(
          "custom",
          options("locale" to feature["locale"].asString()),
          verbatim(
            Json.parseToJsonElement("""{"labels":["park","forest"],"nested":{"null":null}}""")
          ),
        ),
      )
    expressions.forEach { expression ->
      val compiled = expression.compile(ExpressionContext.None)
      val value = StyleValue.Expression(compiled)
      assertEquals(compiled.toStyleJson(), Json.parseToJsonElement(value.encoded.toJson()))
      assertSame(value.encoded, value.encoded, "a compiled property reuses its transport")
    }
  }

  @Test
  fun transport_strings_escape_controls_quotes_backslashes_and_unicode() {
    val text = (0..31).map { it.toChar() }.joinToString("") + "\\\"Café 東京\u2028\u2029"
    val compiled = const(text).compile(ExpressionContext.None)
    assertEquals(
      JsonPrimitive(text),
      Json.parseToJsonElement(StyleValue.Expression(compiled).encoded.toJson()),
    )
    val objectValue = StyleValue.Object(mapOf(text to StyleValue.Expression(compiled)))
    assertEquals(
      JsonObject(mapOf(text to JsonPrimitive(text))),
      Json.parseToJsonElement(objectValue.encoded.toJson()),
    )
  }

  @Test
  fun equivalent_expression_and_json_values_compare_without_extra_property_writes() {
    val compiled = const(listOf("a", "b")).compile(ExpressionContext.None)
    val expression: StyleValue = StyleValue.Expression(compiled)
    val raw: StyleValue = StyleValue.Json(compiled.toStyleJson())
    assertEquals(expression, raw)
    assertEquals(raw, expression)
    assertEquals(expression.hashCode(), raw.hashCode())
    val regularCall: StyleValue =
      StyleValue.Expression(call<StringValue>("concat", const("a")).compile(ExpressionContext.None))
    val verbatimCall: StyleValue =
      StyleValue.Expression(
        call<StringValue>("concat", verbatim(JsonPrimitive("a"))).compile(ExpressionContext.None)
      )
    assertEquals(regularCall, verbatimCall)
    assertEquals(regularCall.hashCode(), verbatimCall.hashCode())
  }

  @Test
  fun transports_reject_non_finite_expression_numbers() {
    listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { number ->
      assertFailsWith<IllegalArgumentException> {
        StyleValue.Expression(const(number).compile(ExpressionContext.None)).encoded
      }
    }
  }
}
