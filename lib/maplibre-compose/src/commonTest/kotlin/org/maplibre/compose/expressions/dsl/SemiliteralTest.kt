package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.SymbolAnchor

class SemiliteralTest {
  @Test
  fun arrays_evaluate_children_but_preserve_nested_literal_arrays() {
    assertEquals(
      """["let","semiliteral_value",["semiliteral",[["number",["get","x"]],["literal",["get","literal-key"]],["let","semiliteral_value",["semiliteral",[["number",["get","y"]],2]],["var","semiliteral_value"]]]],["var","semiliteral_value"]]""",
      styleJson(
        list(
          feature["x"].asNumber(),
          const(listOf("get", "literal-key")),
          list(feature["y"].asNumber(), const(2)),
        )
      ),
    )
    assertEquals(
      """["let","semiliteral_value",["semiliteral",[]],["var","semiliteral_value"]]""",
      styleJson(list<FloatValue>()),
    )
  }

  @Test
  fun construction_snapshots_the_list_and_visits_child_expressions() {
    val input = feature["x"].asNumber()
    val items = mutableListOf<Expression<FloatValue>>(input)
    val expression = list(items)
    items.clear()
    assertEquals(
      """["let","semiliteral_value",["semiliteral",[["number",["get","x"]]]],["var","semiliteral_value"]]""",
      styleJson(expression),
    )
    for (tree in listOf(expression, expression.compile(ExpressionContext.None))) {
      val visited = mutableListOf<Expression<*>>()
      tree.visit { visited += it }
      assertTrue(visited.any { styleJson(it) == """["get","x"]""" })
    }
  }

  @Test
  fun variable_anchor_offsets_compile_each_unit_in_the_layers_context() {
    val context =
      object : ExpressionContext by ExpressionContext.None {
        override val emScale = const(1f)
        override val spScale = const(0.5f)
        override val dpScale = const(0.25f)
      }
    assertEquals(
      """["let","semiliteral_value",["semiliteral",["top",["literal",[1,-2]],"top",["literal",[3,-6]],"left",["literal",[1,-2]]]],["var","semiliteral_value"]]""",
      styleJson(
        textVariableAnchorOffset(
          SymbolAnchor.Top to textOffset(2.sp, (-4).sp),
          SymbolAnchor.Top to textOffset(3.em, (-6).em),
          SymbolAnchor.Left to textOffset(4.dp, (-8).dp),
        ),
        context,
      ),
    )
  }
}
