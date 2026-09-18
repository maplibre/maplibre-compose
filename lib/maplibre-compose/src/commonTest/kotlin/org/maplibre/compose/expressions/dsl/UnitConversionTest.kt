package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.unit.em
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.UnitConversion

class UnitConversionTest {
  @Test
  fun generated_ratios_fold_before_offsets_are_scaled() {
    val context =
      object : ExpressionContext by ExpressionContext.None {
        override val emScale = UnitConversion(const(1f), const(16f), divide = true)
      }
    assertEquals("0.125", styleJson(const(2.em), context))
    assertEquals("""["literal",[-0.25,0.75]]""", styleJson(textOffset((-4).em, 12.em), context))
  }

  @Test
  fun identity_scales_preserve_dynamic_values_and_application_arithmetic() {
    val context =
      object : ExpressionContext by ExpressionContext.None {
        override val emScale = const(1f)
      }
    assertEquals("""["number",["get","size"]]""", styleJson(feature["size"].asNumber().em, context))
    assertEquals("""["*",2,3]""", styleJson((const(2f) * const(3f)).em, context))
    val scale = globalState("scale").asNumber()
    assertEquals(
      """["*",0,["number",["global-state","scale"]]]""",
      styleJson(UnitConversion(const(0f), scale)),
    )
  }

  @Test
  fun inexact_ratios_and_division_by_zero_stay_in_the_engine() {
    assertEquals("""["/",1,3]""", styleJson(UnitConversion(const(1f), const(3f), divide = true)))
    assertEquals("""["/",1,0]""", styleJson(UnitConversion(const(1f), const(0f), divide = true)))
    assertEquals(
      styleJson(const(Float.MAX_VALUE) * const(2f)),
      styleJson(UnitConversion(const(Float.MAX_VALUE), const(2f))),
    )
  }
}
