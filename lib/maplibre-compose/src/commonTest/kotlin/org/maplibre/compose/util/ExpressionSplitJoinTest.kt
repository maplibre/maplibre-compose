package org.maplibre.compose.util

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.contains
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.join
import org.maplibre.compose.expressions.dsl.split

/** Compiles `split` and `join` to the style-spec argument order: input first, separator second. */
class ExpressionSplitJoinTest {

  private fun json(expression: CompiledExpression<*>): String = expression.toStyleJson().toString()

  private fun compiled(expression: Expression<*>) = expression.compile(ExpressionContext.None)

  @Test
  fun split_encodes_the_input_before_the_separator() {
    assertEquals("""["split","needle","e"]""", json(compiled(const("needle").split("e"))))
    assertEquals("""["split","needle","e"]""", json(compiled("needle".split(const("e")))))
  }

  @Test
  fun join_encodes_the_array_before_the_separator() {
    assertEquals(
      """["join",["literal",["latitude","longitude"]],""]""",
      json(compiled(const(listOf("latitude", "longitude")).join(""))),
    )
  }

  @Test
  fun split_feeds_contains_for_a_delimited_list() {
    assertEquals(
      """["in","tea",["split",["string",["get","cuisine"]],";"]]""",
      json(compiled(feature["cuisine"].asString().split(";").contains(const("tea")))),
    )
  }

  @Test
  fun split_and_join_accept_expression_separators() {
    val separator = feature["sep"].asString()
    assertEquals(
      """["split","a;b",["string",["get","sep"]]]""",
      json(compiled("a;b".split(separator))),
    )
    assertEquals(
      """["join",["literal",["a","b"]],["string",["get","sep"]]]""",
      json(compiled(const(listOf("a", "b")).join(separator))),
    )
  }
}
