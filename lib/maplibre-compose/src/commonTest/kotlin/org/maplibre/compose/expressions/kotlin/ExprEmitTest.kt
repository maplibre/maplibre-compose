package org.maplibre.compose.expressions.kotlin

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

class ExprEmitTest {
  private fun json(expression: Expression<*>): String =
    expression.compile(ExpressionContext.None).toStyleJson().toString()

  @Test
  fun lit_encodes_scalars() {
    assertEquals("true", json(ExprEmit.lit(true)))
    assertEquals("\"park\"", json(ExprEmit.lit("park")))
    assertEquals("2.5", json(ExprEmit.lit(2.5)))
  }

  @Test
  fun op_builds_function_calls() {
    val get = ExprEmit.op("get", listOf(ExprEmit.lit("mag")))
    val number = ExprEmit.op("number", listOf(get))
    val cmp = ExprEmit.op(">", listOf(number, ExprEmit.lit(5)))
    assertEquals("[\"\u003e\",[\"number\",[\"get\",\"mag\"]],5]", json(cmp).replace("5.0", "5"))
  }

  @Test
  fun case_and_match() {
    val case =
      ExprEmit.op(
        "case",
        listOf(
          ExprEmit.op(">", listOf(ExprEmit.lit(1), ExprEmit.lit(0))),
          ExprEmit.lit(Color.Red),
          ExprEmit.lit(Color.Yellow),
        ),
      )
    assertEquals("case", (case as org.maplibre.compose.expressions.ast.FunctionCall).name)

    val match =
      ExprEmit.match(
        ExprEmit.lit("park"),
        ExprEmit.lit(Color.Gray),
        listOf(ExprEmit.lit("park"), ExprEmit.lit(Color.Green)),
      )
    assertEquals("match", (match as org.maplibre.compose.expressions.ast.FunctionCall).name)
  }

  @Test
  fun interpolate_and_step() {
    val interpolated =
      ExprEmit.interpolate(
        "interpolate",
        ExprEmit.op("linear", emptyList<Expression<*>>()),
        ExprEmit.op("zoom", emptyList<Expression<*>>()),
        listOf(ExprEmit.lit(5), ExprEmit.lit(2), ExprEmit.lit(10), ExprEmit.lit(8)),
      )
    assertEquals(
      "[\"interpolate\",[\"linear\"],[\"zoom\"],5,2,10,8]",
      json(interpolated).replace(".0", ""),
    )
  }

  @Test
  fun lit_encodes_geojson_as_a_bare_object() {
    val point = Point(Position(longitude = 1.0, latitude = 2.0))
    val encoded = json(ExprEmit.lit(point))
    assertTrue(encoded.startsWith("{"), encoded)
    assertTrue(encoded.contains("Point") || encoded.contains("point"), encoded)
  }

  @Test
  fun image_name_is_a_single_image_operator() {
    val encoded = json(ExprEmit.imageName(ExprEmit.lit("marker")))
    assertEquals("""["image","marker"]""", encoded)
    assertEquals("image", (ExprEmit.imageName(ExprEmit.lit("marker")) as FunctionCall).name)
  }
}
