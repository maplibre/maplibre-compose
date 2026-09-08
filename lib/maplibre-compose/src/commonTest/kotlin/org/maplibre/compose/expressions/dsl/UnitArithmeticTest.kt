package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpPaddingValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.TextUnitValue
import org.maplibre.compose.expressions.value.VectorValue

/**
 * Numbers carry a unit in their type. Arithmetic keeps the unit where MapLibre would, drops it for
 * a ratio, and refuses to mix units. The declared types on each value are part of the test: the
 * compiler checks them.
 */
class UnitArithmeticTest {

  @Test
  fun dp_arithmetic_keeps_the_dp_type() {
    val sum: Expression<DpValue> = const(4.dp) + const(2.5.dp)
    val scaled: Expression<DpValue> = const(4.dp) * const(1.5f)
    val scaledLeft: Expression<DpValue> = const(1.5f) * const(4.dp)
    val quotient: Expression<DpValue> = const(4.dp) / const(2f)
    val remainder: Expression<DpValue> = const(4.dp) % const(3)
    val negated: Expression<DpValue> = -const(4.dp)
    assertEquals("""["+",4,2.5]""", styleJson(sum))
    assertEquals("""["*",4,1.5]""", styleJson(scaled))
    assertEquals("""["*",1.5,4]""", styleJson(scaledLeft))
    assertEquals("""["/",4,2]""", styleJson(quotient))
    assertEquals("""["%",4,3]""", styleJson(remainder))
    assertEquals("""["-",4]""", styleJson(negated))
  }

  @Test
  fun dividing_two_dp_values_yields_a_dimensionless_ratio() {
    val ratio: Expression<FloatValue> = const(3.dp) / const(4.dp)
    val percent: Expression<FloatValue> = ratio * const(100f)
    assertEquals("""["*",["/",3,4],100]""", styleJson(percent))
  }

  @Test
  fun min_max_and_abs_keep_the_unit_of_their_inputs() {
    val floor: Expression<DpValue> = max(const(1.dp), zoom().dp)
    val ceiling: Expression<DpValue> = min(const(1.dp), zoom().dp, const(8.dp))
    val magnitude: Expression<DpValue> = abs(-const(2.5.dp))
    assertEquals("""["max",1,["zoom"]]""", styleJson(floor))
    assertEquals("""["min",1,["zoom"],8]""", styleJson(ceiling))
    assertEquals("""["abs",["-",2.5]]""", styleJson(magnitude))
  }

  @Test
  fun rounding_yields_an_int_that_can_index_a_list() {
    val names = const(listOf("a", "b", "c"))
    val index: Expression<IntValue> = round(zoom() / const(2f))
    val name: Expression<StringValue> = names[index]
    assertEquals(
      """["at",["round",["/",["zoom"],2]],["literal",["a","b","c"]]]""",
      styleJson(name),
    )
    assertEquals("""["floor",["zoom"]]""", styleJson(floor(zoom())))
    assertEquals("""["ceil",["zoom"]]""", styleJson(ceil(zoom())))
  }

  @Test
  fun powers_logs_trigonometry_and_constants_compose() {
    val expression = sqrt(zoom()).pow(2f) + log2(zoom()) * LN_2 - PI / E
    assertEquals(
      """["-",["+",["^",["sqrt",["zoom"]],2],["*",["log2",["zoom"]],["ln2"]]],["/",["pi"],["e"]]]""",
      styleJson(expression),
    )
    val radians = zoom() * PI / const(180f)
    assertEquals("""["sin",["/",["*",["zoom"],["pi"]],180]]""", styleJson(sin(radians)))
    assertEquals("""["atan",["ln",["log10",["zoom"]]]]""", styleJson(atan(ln(log10(zoom())))))
  }

  @Test
  fun durations_compile_to_milliseconds() {
    val total: Expression<MillisecondsValue> = const(1.5.seconds) + const(250.milliseconds)
    val fromZoom: Expression<MillisecondsValue> = zoom().seconds
    val direct: Expression<MillisecondsValue> = zoom().milliseconds
    assertEquals("""["+",1500,250]""", styleJson(total))
    assertEquals("""["*",["zoom"],1000]""", styleJson(fromZoom))
    assertEquals("""["zoom"]""", styleJson(direct))
  }

  @Test
  fun text_units_scale_by_the_compilation_context() {
    val em: Expression<TextUnitValue> = const(1.5.em)
    val sp: Expression<TextUnitValue> = const(12.sp)
    val sum: Expression<TextUnitValue> = const(1.em) + const(2.em)
    val scaled: Expression<TextUnitValue> = const(2.em) * const(3f)
    val fromZoom: Expression<TextUnitValue> = (zoom() / const(4f)).em
    assertEquals("""["*",1.5,16]""", styleJson(em, TextContext))
    assertEquals("""["*",12,1.5]""", styleJson(sp, TextContext))
    assertEquals("""["+",["*",1,16],["*",2,16]]""", styleJson(sum, TextContext))
    assertEquals("""["*",["*",2,16],3]""", styleJson(scaled, TextContext))
    assertEquals("""["*",["/",["zoom"],4],16]""", styleJson(fromZoom, TextContext))
  }

  @Test
  fun text_units_need_a_context_that_provides_a_scale() {
    assertFailsWith<IllegalStateException> { styleJson(const(1.em)) }
  }

  @Test
  fun interpolation_over_zoom_keeps_the_stop_unit_and_sorts_stops() {
    val width: Expression<DpValue> =
      interpolate(exponential(1.5f), zoom(), 18 to const(32.dp), 5 to const(1.dp))
    assertEquals("""["interpolate",["exponential",1.5],["zoom"],5,1,18,32]""", styleJson(width))

    val size: Expression<TextUnitValue> =
      interpolate(linear(), zoom(), 10 to const(1.em), 14 to const(1.5.em))
    assertEquals(
      """["interpolate",["linear"],["zoom"],10,["*",1,16],14,["*",1.5,16]]""",
      styleJson(size, TextContext),
    )

    val eased: Expression<FloatValue> =
      interpolate(cubicBezier(0.4f, 0f, 0.6f, 1f), zoom(), 0 to const(0f), 22 to const(1f))
    assertEquals(
      """["interpolate",["cubic-bezier",0.4,0,0.6,1],["zoom"],0,0,22,1]""",
      styleJson(eased),
    )
  }

  @Test
  fun vectors_interpolate_and_index_as_numbers() {
    val offset: Expression<DpOffsetValue> =
      interpolate(linear(), zoom(), 5 to offset(0.dp, 1.dp), 10 to offset(0.dp, 2.5.dp))
    assertEquals(
      """["interpolate",["linear"],["zoom"],5,["literal",[0,1]],10,["literal",[0,2.5]]]""",
      styleJson(offset),
    )

    val position: Expression<VectorValue<Number>> = const(listOf(1.15f, 210f, 30f))
    val azimuth: Expression<FloatValue> = position[1]
    assertEquals("""["at",1,["literal",[1.15,210,30]]]""", styleJson(azimuth))

    val padding: Expression<DpPaddingValue> = padding(1.dp, 2.dp, 3.dp, 4.dp)
    assertEquals("""["literal",[2,3,4,1]]""", styleJson(padding))
  }
}
