package org.maplibre.compose.util

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.switch

/** Compiles condition `switch` to the style-spec `case` operator. */
class ExpressionSwitchTest {

  private fun json(expression: CompiledExpression<*>): String = expression.toStyleJson().toString()

  private fun compiled(expression: Expression<*>) = expression.compile(ExpressionContext.None)

  @Test
  fun switch_with_no_conditions_is_the_fallback() {
    assertEquals("\"fallback\"", json(compiled(switch(fallback = const("fallback")))))
  }

  @Test
  fun switch_with_one_condition_is_a_single_case() {
    assertEquals(
      """["case",true,"yes","no"]""",
      json(compiled(switch(condition(const(true), const("yes")), fallback = const("no")))),
    )
  }

  @Test
  fun switch_with_several_conditions_is_one_case() {
    assertEquals(
      """["case",["==",["string",["get","icon"]],"1"],["image","one"],""" +
        """["==",["string",["get","icon"]],"2"],["image","two"],""" +
        """["==",["string",["get","icon"]],"3"],["image","three"],""" +
        """["image","fallback"]]""",
      json(compiled(iconSwitch())),
    )
  }

  private fun iconSwitch() =
    switch(
      condition(feature["icon"].asString() eq const("1"), image("one")),
      condition(feature["icon"].asString() eq const("2"), image("two")),
      condition(feature["icon"].asString() eq const("3"), image("three")),
      fallback = image("fallback"),
    )
}
