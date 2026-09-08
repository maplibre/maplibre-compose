package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.ExpressionType
import org.maplibre.compose.expressions.value.FormattedValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.VectorValue

/** Lists, maps, strings, and formatted text keep their item and result types through each step. */
class CollectionsAndStringsTest {

  private val tags: Expression<ListValue<StringValue>> = const(listOf("a", "b", "c"))
  private val tagsJson = """["literal",["a","b","c"]]"""
  private val name = feature["name"].asString()
  private val nameJson = """["string",["get","name"]]"""

  @Test
  fun list_items_keep_the_item_type() {
    val second: Expression<StringValue> = tags[1]
    assertEquals("""["upcase",["at",1,$tagsJson]]""", styleJson(second.uppercase()))
    val byIndex: Expression<StringValue> = tags[round(zoom())]
    assertEquals("""["at",["round",["zoom"]],$tagsJson]""", styleJson(byIndex))
  }

  @Test
  fun list_lengths_are_ints_that_take_part_in_arithmetic() {
    val last: Expression<StringValue> = tags[tags.length() - const(1)]
    assertEquals("""["at",["-",["length",$tagsJson],1],$tagsJson]""", styleJson(last))
    val tail: Expression<ListValue<StringValue>> = tags.slice(const(1), tags.length())
    assertEquals("""["slice",$tagsJson,1,["length",$tagsJson]]""", styleJson(tail))
    assertEquals("""["slice",$tagsJson,1,2]""", styleJson(tags.slice(1, 2)))
  }

  @Test
  fun membership_and_search_work_on_lists_and_strings() {
    assertEquals("""["in","b",$tagsJson]""", styleJson(tags.contains(const("b"))))
    assertEquals("""["in",["get","kind"],$tagsJson]""", styleJson(tags.contains(feature["kind"])))
    assertEquals(
      """["index-of","b",$tagsJson,1]""",
      styleJson(tags.indexOf(const("b"), startIndex = 1)),
    )
    assertEquals("""["in","-",$nameJson]""", styleJson(name.contains("-")))
    assertEquals("""["in",$nameJson,"abc"]""", styleJson("abc".contains(name)))
    assertEquals(
      """[">=",["index-of","-",$nameJson],0]""",
      styleJson(name.indexOf("-") gte const(0)),
    )
  }

  @Test
  fun strings_chain_transformations() {
    val shortened: Expression<StringValue> = name.lowercase().substring(0, 3) + const("…")
    assertEquals(
      """["concat",["slice",["downcase",$nameJson],0,3],"…"]""",
      styleJson(shortened),
    )
    val firstPart: Expression<StringValue> = name.split(";")[0]
    assertEquals("""["at",0,["split",$nameJson,";"]]""", styleJson(firstPart))
    val rejoined: Expression<StringValue> = name.split(const(";")).slice(0, 2).join(" / ")
    assertEquals("""["join",["slice",["split",$nameJson,";"],0,2]," / "]""", styleJson(rejoined))
    assertEquals("""["is-supported-script",$nameJson]""", styleJson(name.isScriptSupported()))
    assertEquals(
      """["resolved-locale",["collator",{"locale":"fr"}]]""",
      styleJson(resolvedLocale(collator(locale = "fr"))),
    )
  }

  @Test
  fun maps_are_read_by_key() {
    assertEquals("""["has","name",["properties"]]""", styleJson(feature.properties().has("name")))
    assertEquals("""["get","name",["properties"]]""", styleJson(feature.properties()["name"]))
    val street: Expression<StringValue> = feature["addr"].asMap()["street"].asString()
    assertEquals("""["string",["get","street",["object",["get","addr"]]]]""", styleJson(street))
  }

  @Test
  fun array_assertions_put_the_value_last_as_the_spec_requires() {
    assertEquals("""["array",["get","tags"]]""", styleJson(feature["tags"].asList()))
    assertEquals(
      """["array","string",["get","tags"]]""",
      styleJson(feature["tags"].asList(ExpressionType.String)),
    )
    assertEquals(
      """["array","boolean",2,["get","flags"]]""",
      styleJson(feature["flags"].asList(ExpressionType.Boolean, length = 2)),
    )
    val vector: Expression<VectorValue<Number>> = feature["v"].asVector(length = 3)
    assertEquals("""["array","number",3,["get","v"]]""", styleJson(vector))
    val dps: Expression<VectorValue<Dp>> = feature["v"].asVector()
    assertEquals("""["array","number",["get","v"]]""", styleJson(dps))
    assertFailsWith<IllegalArgumentException> { feature["v"].asList(ExpressionType.Color) }
    assertFailsWith<IllegalArgumentException> { feature["v"].asList(length = 2) }
    assertEquals("""["array","number",2,["get","o"]]""", styleJson(feature["o"].asOffset()))
    assertEquals("""["array","number",2,["get","o"]]""", styleJson(feature["o"].asDpOffset()))
    assertEquals("""["array","number",["get","p"]]""", styleJson(feature["p"].asPadding()))
  }

  @Test
  fun format_mixes_text_images_and_styling() {
    val label: Expression<FormattedValue> =
      format(
        span(name, textSize = const(1.25.em), textColor = const(Color.Red)),
        span(" ", textFont = listOf("Noto Sans Bold")),
        span(image("star")),
        span(feature["rank"].asNumber().formatToString(locale = "en", maxFractionDigits = 0)),
      )
    assertEquals(
      """["format",$nameJson,{"text-color":"rgba(255, 0, 0, 1)","font-scale":["*",1.25,16]},""" +
        """" ",{"text-font":["literal",["Noto Sans Bold"]]},["image","star"],{},""" +
        """["number-format",["number",["get","rank"]],{"locale":"en","max-fraction-digits":0}],{}]""",
      styleJson(label, TextContext),
    )
  }

  @Test
  fun number_formatting_accepts_expression_options() {
    val price: Expression<StringValue> =
      feature["price"]
        .asNumber()
        .formatToString(
          locale = feature["lang"].asString(),
          currency = const("EUR"),
          minFractionDigits = const(2),
          maxFractionDigits = round(zoom()),
        )
    assertEquals(
      """["number-format",["number",["get","price"]],""" +
        """{"locale":["string",["get","lang"]],"currency":"EUR",""" +
        """"min-fraction-digits":2,"max-fraction-digits":["round",["zoom"]]}]""",
      styleJson(price),
    )
    assertEquals(
      """["number-format",["zoom"],{"locale":"en"}]""",
      styleJson(zoom().formatToString(locale = "en")),
    )
    assertEquals("""["number-format",["zoom"],{}]""", styleJson(zoom().formatToString()))
    assertEquals("""["resolved-locale",["collator",{}]]""", styleJson(resolvedLocale(collator())))
  }

  @Test
  fun literal_lists_and_offsets_render_as_literal_arrays() {
    assertEquals("""["literal",[1,2.5]]""", styleJson(const(listOf(1f, 2.5f))))
    assertEquals("""["literal",[1,2]]""", styleJson(offset(1f, 2f)))
    assertEquals("""["literal",[1,2]]""", styleJson(offset(1.dp, 2.dp)))
    assertEquals(
      """["literal",["round","butt"]]""",
      styleJson(const(listOf(LineCap.Round, LineCap.Butt))),
    )
    assertEquals(
      """["literal",[[1,2],[3,4]]]""",
      styleJson(const(listOf(const(listOf(1f, 2f)), const(listOf(3f, 4f))))),
    )
    assertEquals(
      """["literal",["top",[0,1.5],"left",[-1,0]]]""",
      styleJson(
        textVariableAnchorOffset(
          SymbolAnchor.Top to Offset(0f, 1.5f),
          SymbolAnchor.Left to Offset(-1f, 0f),
        )
      ),
    )
  }

  @Test
  fun colours_are_built_from_and_broken_into_components() {
    val opaque: Expression<ColorValue> = rgbColor(const(255), const(0), const(0))
    assertEquals("""["rgb",255,0,0]""", styleJson(opaque))
    val fading: Expression<ColorValue> =
      rgbColor(const(255), const(0), const(0), alpha = zoom() / const(22f))
    assertEquals("""["rgba",255,0,0,["/",["zoom"],22]]""", styleJson(fading))
    val components: Expression<VectorValue<Number>> = const(Color.Red).toRgbaComponents()
    assertEquals(
      """["<",["at",3,["to-rgba","rgba(255, 0, 0, 1)"]],0.5]""",
      styleJson(components[3] lt const(0.5f)),
    )
  }

  @Test
  fun conversions_accept_any_input_and_optional_fallbacks() {
    assertEquals(
      """["concat","n=",["to-string",["get","count"]]]""",
      styleJson(const("n=") + feature["count"].convertToString()),
    )
    assertEquals(
      """["to-number",["get","w"],1]""",
      styleJson(feature["w"].convertToNumber(const(1f))),
    )
    assertEquals("""["to-boolean",["get","on"]]""", styleJson(feature["on"].convertToBoolean()))
    assertEquals(
      """["to-color",["get","c"],"rgba(0, 0, 0, 1)"]""",
      styleJson(feature["c"].convertToColor(const(Color.Black))),
    )
  }
}
