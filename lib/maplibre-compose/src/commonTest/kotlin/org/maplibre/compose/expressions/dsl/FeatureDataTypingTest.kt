package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.ExpressionType
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.FormattedValue
import org.maplibre.compose.expressions.value.GeometryType
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.VectorValue

/**
 * Feature data enters an expression without a known type. These tests cover the ways it gains one
 * and the inputs that already have one.
 */
class FeatureDataTypingTest {

  @Test
  fun a_property_s_type_can_be_inspected() {
    assertEquals(
      """["==",["typeof",["get","x"]],"string"]""",
      styleJson(feature["x"].type() eq const(ExpressionType.String)),
    )
    val isNumber: Expression<BooleanValue> =
      switch(feature["x"].type(), case(ExpressionType.Number, const(true)), fallback = const(false))
    assertEquals("""["match",["typeof",["get","x"]],"number",true,false]""", styleJson(isNumber))
  }

  @Test
  fun assertions_take_fallbacks_in_order() {
    assertEquals(
      """["number",["get","h"],["get","height"],0]""",
      styleJson(feature["h"].asNumber(feature["height"], const(0f))),
    )
    assertEquals(
      """["string",["get","name:en"],["get","name"],"?"]""",
      styleJson(feature["name:en"].asString(feature["name"], const("?"))),
    )
    assertEquals(
      """["boolean",["feature-state","hover"],false]""",
      styleJson(feature.state("hover").asBoolean(const(false))),
    )
  }

  @Test
  fun feature_state_drives_paint() {
    val opacity: Expression<FloatValue> =
      switch(
        condition(feature.state("hover").asBoolean(const(false)), const(1f)),
        fallback = const(0.5f),
      )
    assertEquals(
      """["case",["boolean",["feature-state","hover"],false],1,0.5]""",
      styleJson(opacity),
    )
    assertEquals(
      """["==",["feature-state","selected"],true]""",
      styleJson(feature.state("selected") eq const(true)),
    )
  }

  @Test
  fun geometry_and_identity_inputs_have_their_own_types() {
    assertEquals(
      """["==",["geometry-type"],"Point"]""",
      styleJson(feature.geometryType() eq const(GeometryType.Point)),
    )
    val isArea: Expression<BooleanValue> =
      switch(
        feature.geometryType(),
        case(GeometryType.Polygon, const(true)),
        fallback = const(false),
      )
    assertEquals("""["match",["geometry-type"],"Polygon",true,false]""", styleJson(isArea))
    assertEquals("""["to-string",["id"]]""", styleJson(feature.id().convertToString()))
    assertEquals(
      """["+",["number",["accumulated"]],["number",["get","pop"]]]""",
      styleJson(feature.accumulated().asNumber() + feature["pop"].asNumber()),
    )
  }

  @Test
  fun variables_bind_once_and_are_reused() {
    val label: Expression<StringValue> =
      withVariable("n", feature["name"].asString()) { n ->
        switch(
          condition(n.use().length() gt const(10), n.use().substring(0, 10) + const("…")),
          fallback = n.use(),
        )
      }
    assertEquals(
      """["let","n",["string",["get","name"]],["case",[">",["length",["var","n"]],10],""" +
        """["concat",["slice",["var","n"],0,10],"…"],["var","n"]]]""",
      styleJson(label),
    )

    val area: Expression<FloatValue> =
      withVariable("w", feature["w"].asNumber()) { w ->
        withVariable("h", feature["h"].asNumber()) { h -> w.use() * h.use() }
      }
    assertEquals(
      """["let","w",["number",["get","w"]],["let","h",["number",["get","h"]],""" +
        """["*",["var","w"],["var","h"]]]]""",
      styleJson(area),
    )

    val scaled: Expression<DpValue> = withVariable("z", zoom()) { z -> z.use().dp * const(2f) }
    assertEquals("""["let","z",["zoom"],["*",["var","z"],2]]""", styleJson(scaled))
  }

  @Test
  fun images_are_built_from_names_or_expressions() {
    assertEquals(
      """["image",["concat","icon-",["string",["get","kind"]]]]""",
      styleJson(image(const("icon-") + feature["kind"].asString())),
    )
    val icon: Expression<ImageValue> =
      switch(feature["kind"].asString(), case("park", image("tree")), fallback = image("dot"))
    assertEquals(
      """["match",["string",["get","kind"]],"park",["image","tree"],["image","dot"]]""",
      styleJson(icon),
    )
    val none: Expression<ImageValue> =
      switch(
        condition(feature.has("icon"), image(feature["icon"].asString())),
        fallback = image(""),
      )
    assertEquals(
      """["case",["has","icon"],["image",["string",["get","icon"]]],["image",""]]""",
      styleJson(none),
    )
  }

  @Test
  fun filters_combine_geometry_property_and_zoom_tests() {
    val filter: Expression<BooleanValue> =
      all(
        feature.geometryType() eq const(GeometryType.Polygon),
        feature["class"] neq const("water"),
        feature.has("name"),
        zoom() gte const(12) or (feature["rank"].asNumber() lte const(3)),
        const(listOf("park", "garden")).contains(feature["subclass"]),
      )
    assertEquals(
      """["all",["==",["geometry-type"],"Polygon"],["!=",["get","class"],"water"],["has","name"],""" +
        """["any",[">=",["zoom"],12],["<=",["number",["get","rank"]],3]],""" +
        """["in",["get","subclass"],["literal",["park","garden"]]]]""",
      styleJson(filter),
    )
  }

  @Test
  fun cluster_labels_fall_back_to_the_feature_name() {
    val text: Expression<StringValue> =
      switch(
        condition(feature.has("point_count"), feature["point_count_abbreviated"].convertToString()),
        fallback = feature["name"].asString(),
      )
    assertEquals(
      """["case",["has","point_count"],["to-string",["get","point_count_abbreviated"]],""" +
        """["string",["get","name"]]]""",
      styleJson(text),
    )
    val radius: Expression<DpValue> =
      step(
        feature["point_count"].asNumber(),
        const(15.dp),
        100 to const(20.dp),
        750 to const(25.dp),
      )
    assertEquals(
      """["step",["number",["get","point_count"]],15,100,20,750,25]""",
      styleJson(radius),
    )
  }

  @Test
  fun value_types_widen_along_the_hierarchy() {
    // Each assignment is a compile-time check of a subtype relation the DSL relies on.
    val int: Expression<IntValue> = const(1)
    val float: Expression<FloatValue> = int
    val cap: Expression<LineCap> = const(LineCap.Round)
    val string: Expression<StringValue> = cap
    val formatted: Expression<FormattedValue> = string
    val offset: Expression<DpOffsetValue> = offset(1.dp, 2.dp)
    val vector: Expression<VectorValue<androidx.compose.ui.unit.Dp>> = offset
    val numbers: Expression<ListValue<DpValue>> = vector
    val untyped: Expression<*> = numbers
    assertEquals("""["literal",[1,2]]""", styleJson(untyped))
    assertEquals("1", styleJson(float))
    assertEquals("\"round\"", styleJson(formatted))
  }
}
