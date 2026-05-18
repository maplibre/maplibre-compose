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
import org.maplibre.compose.expressions.ast.CompiledListLiteral
import org.maplibre.compose.expressions.ast.CompiledMapLiteral
import org.maplibre.compose.expressions.ast.CompiledOptions
import org.maplibre.compose.expressions.ast.DpPaddingLiteral
import org.maplibre.compose.expressions.ast.FloatLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.OffsetLiteral
import org.maplibre.compose.expressions.ast.StringLiteral

class ExpressionEncoderTest {

  @Test
  fun nullLiteralReturnsJsonNull() {
    assertEquals("null", NullLiteral.toJsonString())
  }

  @Test
  fun floatLiteralEncodesToNumber() {
    assertEquals("5.0", FloatLiteral.of(5f).toJsonString())
    assertEquals("0.0", FloatLiteral.of(0f).toJsonString())
    assertEquals("-3.5", FloatLiteral.of(-3.5f).toJsonString())
  }

  @Test
  fun stringLiteralEncodesToJsonString() {
    assertEquals("\"hello\"", StringLiteral.of("hello").toJsonString())
    assertEquals("\"\"", StringLiteral.of("").toJsonString())
    assertEquals("\"with spaces\"", StringLiteral.of("with spaces").toJsonString())
  }

  @Test
  fun booleanLiteralEncodesToBoolean() {
    assertEquals("true", BooleanLiteral.of(true).toJsonString())
    assertEquals("false", BooleanLiteral.of(false).toJsonString())
  }

  @Test
  fun offsetLiteralEncodesToLiteralArray() {
    val result = OffsetLiteral.of(Offset(10f, 20f)).toJsonString()
    assertEquals("[\"literal\",[10.0,20.0]]", result)
  }

  @Test
  fun offsetZeroEncodesToLiteralArray() {
    val result = OffsetLiteral.of(Offset.Zero).toJsonString()
    assertEquals("[\"literal\",[0.0,0.0]]", result)
  }

  @Test
  fun colorLiteralEncodesToRgba() {
    val red = ColorLiteral.of(Color.Red)
    assertEquals("\"rgba(255, 0, 0, 1.0)\"", red.toJsonString())
  }

  @Test
  fun colorTransparentEncodesToRgba() {
    val transparent = ColorLiteral.of(Color.Transparent)
    assertEquals("\"rgba(0, 0, 0, 0.0)\"", transparent.toJsonString())
  }

  @Test
  fun colorWithAlphaEncodesToRgba() {
    val color = ColorLiteral.of(Color(0.5f, 0.5f, 0.5f, 0.5f))
    val result = color.toJsonString()!!
    // Should start with "rgba(" and end with ")"
    assert(result.startsWith("\"rgba(")) { "Expected rgba format, got: $result" }
    assert(result.endsWith(")\"")) { "Expected rgba format, got: $result" }
    // RGB channels should be 128 (0.5 * 255 ≈ 128)
    assert("128, 128, 128" in result) { "Expected rgb of 128, got: $result" }
  }

  @Test
  fun dpPaddingLiteralEncodesToLiteralArray() {
    // PaddingValues.Absolute(left, top, right, bottom)
    // Encoder outputs [top, right, bottom, left] (CSS order)
    val padding = DpPaddingLiteral.of(PaddingValues.Absolute(4.dp, 1.dp, 2.dp, 3.dp))
    val result = padding.toJsonString()
    assertEquals("[\"literal\",[1.0,2.0,3.0,4.0]]", result)
  }

  @Test
  fun simpleFunctionCallEncodesToArray() {
    // ["get", "name"]
    val expr = CompiledFunctionCall.of("get", listOf(StringLiteral.of("name")))
    assertEquals("[\"get\",\"name\"]", expr.toJsonString())
  }

  @Test
  fun nestedFunctionCallEncodesToNestedArray() {
    // ["==", ["get", "type"], "park"]
    val getExpr = CompiledFunctionCall.of("get", listOf(StringLiteral.of("type")))
    val eqExpr = CompiledFunctionCall.of("==", listOf(getExpr, StringLiteral.of("park")))
    assertEquals("[\"==\",[\"get\",\"type\"],\"park\"]", eqExpr.toJsonString())
  }

  @Test
  fun interpolateExpressionEncodesToArray() {
    // ["interpolate", ["linear"], ["zoom"], 0, 1, 22, 20]
    val linear = CompiledFunctionCall.of("linear", emptyList())
    val zoom = CompiledFunctionCall.of("zoom", emptyList())
    val expr =
      CompiledFunctionCall.of(
        "interpolate",
        listOf(
          linear,
          zoom,
          FloatLiteral.of(0f),
          FloatLiteral.of(1f),
          FloatLiteral.of(22f),
          FloatLiteral.of(20f),
        ),
      )
    assertEquals("[\"interpolate\",[\"linear\"],[\"zoom\"],0.0,1.0,22.0,20.0]", expr.toJsonString())
  }

  @Test
  fun caseExpressionEncodesToArray() {
    // ["case", ["==", ["get", "type"], "park"], "green", "gray"]
    val getExpr = CompiledFunctionCall.of("get", listOf(StringLiteral.of("type")))
    val condition = CompiledFunctionCall.of("==", listOf(getExpr, StringLiteral.of("park")))
    val expr =
      CompiledFunctionCall.of(
        "case",
        listOf(condition, StringLiteral.of("green"), StringLiteral.of("gray")),
      )
    assertEquals(
      "[\"case\",[\"==\",[\"get\",\"type\"],\"park\"],\"green\",\"gray\"]",
      expr.toJsonString(),
    )
  }

  @Test
  fun functionCallWithLiteralArgWrapsInLiteral() {
    // When isLiteralArg returns true for index 0, the arg should be treated as literal context
    val listArg =
      CompiledFunctionCall.of(
        "match",
        listOf(
          CompiledFunctionCall.of("get", listOf(StringLiteral.of("type"))),
          StringLiteral.of("park"),
          StringLiteral.of("green"),
          StringLiteral.of("gray"),
        ),
      )
    val result = listArg.toJsonString()
    assertEquals("[\"match\",[\"get\",\"type\"],\"park\",\"green\",\"gray\"]", result)
  }

  @Test
  fun compiledListLiteralEncodesToLiteralArray() {
    val list =
      CompiledListLiteral.of(
        listOf(StringLiteral.of("a"), StringLiteral.of("b"), StringLiteral.of("c"))
      )
    assertEquals("[\"literal\",[\"a\",\"b\",\"c\"]]", list.toJsonString())
  }

  @Test
  fun compiledMapLiteralEncodesToLiteralObject() {
    val map =
      CompiledMapLiteral.of(
        mapOf("key" to StringLiteral.of("value"), "num" to FloatLiteral.of(42f))
      )
    assertEquals("[\"literal\",{\"key\":\"value\",\"num\":42.0}]", map.toJsonString())
  }

  @Test
  fun compiledOptionsEncodesToPlainObject() {
    @Suppress("UNCHECKED_CAST")
    val opts =
      CompiledOptions.of(
        mapOf(
          "font-scale" to FloatLiteral.of(1.2f) as CompiledExpression<Nothing>,
          "text-font" to StringLiteral.of("Arial") as CompiledExpression<Nothing>,
        )
      )
    assertEquals("{\"font-scale\":1.2,\"text-font\":\"Arial\"}", opts.toJsonString())
  }
}
