package org.maplibre.compose.expressions.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.FloatValue

class CallTest {
  @Test
  fun call_encodes_an_operator_the_dsl_does_not_provide() {
    val noise: Expression<FloatValue> =
      call("custom-noise", feature["seed"].asNumber(), const(listOf(1, 2)))
    assertEquals(
      """["custom-noise",["number",["get","seed"]],["literal",[1,2]]]""",
      styleJson(noise),
    )
  }

  @Test
  fun call_composes_with_other_expressions() {
    assertEquals(
      """["*",["custom-noise"],2]""",
      styleJson(call<FloatValue>("custom-noise") * const(2f)),
    )
  }
}
