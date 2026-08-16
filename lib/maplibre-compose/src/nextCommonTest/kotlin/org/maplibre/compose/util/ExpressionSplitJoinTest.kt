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
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.StringValue

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
  fun split_encodes_an_empty_separator() {
    assertEquals("""["split","string",""]""", json(compiled(const("string").split(""))))
  }

  @Test
  fun split_encodes_a_feature_property_input() {
    assertEquals(
      """["split",["string",["get","name"]],";"]""",
      json(compiled(feature["name"].asString().split(";"))),
    )
  }

  @Test
  fun join_encodes_the_array_before_the_separator() {
    assertEquals(
      """["join",["literal",["latitude","longitude"]],""]""",
      json(compiled(const(listOf("latitude", "longitude")).join(""))),
    )
  }

  @Test
  fun join_encodes_empty_items() {
    assertEquals(
      """["join",["literal",["","latitude","","","longitude",""]],","]""",
      json(compiled(const(listOf("", "latitude", "", "", "longitude", "")).join(","))),
    )
  }

  @Test
  fun join_encodes_a_feature_property_array() {
    assertEquals(
      """["join",["get","haystack"],"+"]""",
      json(compiled(feature["haystack"].cast<ListValue<StringValue>>().join("+"))),
    )
  }

  @Test
  fun join_of_split_keeps_both_operators() {
    assertEquals(
      """["join",["split",["string",["get","name"]],";"],"\n"]""",
      json(compiled(feature["name"].asString().split(";").join("\n"))),
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
