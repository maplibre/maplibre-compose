package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.StringValue

/**
 * Decision expressions take their output type from their branches. Every branch must agree, and the
 * result can feed any other expression of that type, including another decision or a ramp.
 */
class DecisionTypingTest {

  private val red = "\"rgba(255, 0, 0, 1)\""
  private val blue = "\"rgba(0, 0, 255, 1)\""

  @Test
  fun nested_switches_share_one_output_type() {
    val color: Expression<ColorValue> =
      switch(
        condition(feature.has("colour"), feature["colour"].convertToColor()),
        condition(
          feature["kind"] eq const("park"),
          switch(condition(zoom() gt const(12f), const(Color.Green)), fallback = const(Color.Gray)),
        ),
        fallback = const(Color.Black),
      )
    assertEquals(
      """["case",["has","colour"],["to-color",["get","colour"]],""" +
        """["==",["get","kind"],"park"],""" +
        """["case",[">",["zoom"],12],"rgba(0, 255, 0, 1)","rgba(136, 136, 136, 1)"],""" +
        """"rgba(0, 0, 0, 1)"]""",
      styleJson(color),
    )
  }

  @Test
  fun match_labels_can_be_strings_numbers_or_enums() {
    val byName: Expression<FloatValue> =
      switch(
        feature["class"].asString(),
        case("motorway", const(4f)),
        case(listOf("trunk", "primary"), const(3f)),
        fallback = const(1f),
      )
    assertEquals(
      """["match",["string",["get","class"]],"motorway",4,["trunk","primary"],3,1]""",
      styleJson(byName),
    )

    val byRank: Expression<StringValue> =
      switch(
        feature["rank"].asNumber(),
        case(1, const("gold")),
        case(listOf(2, 3), const("silver")),
        fallback = const("none"),
      )
    assertEquals(
      """["match",["number",["get","rank"]],1,"gold",[2,3],"silver","none"]""",
      styleJson(byRank),
    )

    val capJson =
      """["string",["case",["in",["get","cap"],["literal",["butt","round","square"]]],["get","cap"],null]]"""
    val byCap: Expression<DpValue> =
      switch(
        feature["cap"].asEnum<LineCap>(),
        case(LineCap.Round, const(2.dp)),
        case(listOf(LineCap.Butt, LineCap.Square), const(1.dp)),
        case("bevel", const(3.dp)),
        fallback = const(0.dp),
      )
    assertEquals(
      """["match",$capJson,"round",2,["butt","square"],1,"bevel",3,0]""",
      styleJson(byCap),
    )
  }

  @Test
  fun step_and_interpolate_nest_inside_each_other() {
    val radius: Expression<DpValue> =
      interpolate(
        linear(),
        zoom(),
        5 to step(feature["mag"].asNumber(), const(2.dp), 4 to const(4.dp), 6 to const(8.dp)),
        12 to const(16.dp),
      )
    assertEquals(
      """["interpolate",["linear"],["zoom"],5,["step",["number",["get","mag"]],2,4,4,6,8],12,16]""",
      styleJson(radius),
    )

    val opacity: Expression<FloatValue> =
      step(
        zoom(),
        const(0f),
        10 to interpolate(linear(), feature["mag"].asNumber(), 0 to const(0.2f), 8 to const(1f)),
      )
    assertEquals(
      """["step",["zoom"],0,10,["interpolate",["linear"],["number",["get","mag"]],0,0.2,8,1]]""",
      styleJson(opacity),
    )
  }

  @Test
  fun a_switch_can_choose_between_whole_ramps() {
    val width: Expression<DpValue> =
      switch(
        condition(
          feature["class"] eq const("motorway"),
          interpolate(exponential(1.5f), zoom(), 5 to const(1.dp), 18 to const(32.dp)),
        ),
        condition(feature["class"] eq const("path"), const(1.dp)),
        fallback = interpolate(linear(), zoom(), 5 to const(0.5.dp), 18 to const(12.dp)),
      )
    assertEquals(
      """["case",["==",["get","class"],"motorway"],""" +
        """["interpolate",["exponential",1.5],["zoom"],5,1,18,32],""" +
        """["==",["get","class"],"path"],1,""" +
        """["interpolate",["linear"],["zoom"],5,0.5,18,12]]""",
      styleJson(width),
    )
  }

  @Test
  fun colour_interpolation_has_three_colour_spaces() {
    val stops = arrayOf(0 to const(Color.Red), 1 to const(Color.Blue))
    assertEquals(
      """["interpolate",["linear"],["heatmap-density"],0,$red,1,$blue]""",
      styleJson(interpolate(linear(), heatmapDensity(), *stops)),
    )
    assertEquals(
      """["interpolate-hcl",["linear"],["line-progress"],0,$red,1,$blue]""",
      styleJson(interpolateHcl(linear(), feature.lineProgress(), *stops)),
    )
    assertEquals(
      """["interpolate-lab",["linear"],["elevation"],0,$red,1,$blue]""",
      styleJson(interpolateLab(linear(), elevation(), *stops)),
    )
  }

  @Test
  fun boolean_operators_combine_infix_and_prefix_forms() {
    val visible: Expression<BooleanValue> =
      (feature.has("name") and (zoom() gte const(12))) or
        !(feature["hidden"].asBoolean(const(false)))
    assertEquals(
      """["any",["all",["has","name"],[">=",["zoom"],12]],["!",["boolean",["get","hidden"],false]]]""",
      styleJson(visible),
    )
    assertEquals(
      """["all",true,false,["has","x"]]""",
      styleJson(all(const(true), const(false), feature.has("x"))),
    )
    assertEquals("""["any"]""", styleJson(any()))
  }

  @Test
  fun ordering_accepts_numbers_strings_and_a_collator() {
    assertEquals("""[">",["zoom"],5]""", styleJson(zoom() gt const(5)))
    assertEquals(
      """["<=",["number",["get","rank"]],3.5]""",
      styleJson(feature["rank"].asNumber() lte const(3.5f)),
    )
    assertEquals("""["<","a","b"]""", styleJson(const("a") lt const("b")))
    assertEquals("""["!=",1,2]""", styleJson(const(1) neq const(2)))
    assertEquals(
      """[">=",["string",["get","a"]],["string",["get","b"]],""" +
        """["collator",{"case-sensitive":false,"locale":"de"}]]""",
      styleJson(
        gte(
          feature["a"].asString(),
          feature["b"].asString(),
          collator(caseSensitive = false, locale = "de"),
        )
      ),
    )
    assertEquals(
      """["==","straße","STRASSE",["collator",{"case-sensitive":false,"diacritic-sensitive":false}]]""",
      styleJson(
        eq(
          const("straße"),
          const("STRASSE"),
          collator(caseSensitive = false, diacriticSensitive = false),
        )
      ),
    )
  }

  @Test
  fun coalesce_takes_the_first_non_null_value() {
    val label: Expression<StringValue> =
      coalesce(feature["name:en"], feature["name"]).convertToString()
    assertEquals(
      """["to-string",["coalesce",["get","name:en"],["get","name"]]]""",
      styleJson(label),
    )

    val width: Expression<FloatValue> =
      coalesce(feature["width"].cast<FloatValue?>(), fallback = const(1f))
    assertEquals("""["coalesce",["get","width"],1]""", styleJson(width))
    val maybeWidth: Expression<FloatValue?> = coalesce(feature["width"].cast<FloatValue?>())
    assertEquals("""["coalesce",["get","width"]]""", styleJson(maybeWidth))
  }
}
