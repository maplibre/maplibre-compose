package org.maplibre.compose.expressions.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.AnyValue
import org.maplibre.compose.expressions.value.EquatableValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.FormattedValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.NullValue
import org.maplibre.compose.expressions.value.StringValue

/**
 * A nullable type argument marks an expression that may evaluate to null. Only a source of unknown
 * type produces one, and only a function MapLibre defines for a null input accepts one. The
 * declared types are the contract; the compiler checks them.
 *
 * The compiler rejects each of these, and MapLibre would reject the style or abort the expression:
 * - `switch(condition(test, const("a")), fallback = nil())` as a layer property: a `null` literal
 *   is a [NullValue], not a nullable string.
 * - `feature["name"].cast<StringValue?>().uppercase()`: `upcase` needs a string.
 * - `feature["rank"].cast<FloatValue?>() gt const(1f)`: ordering needs a value.
 * - `interpolate(linear(), zoom(), 0 to feature["w"].cast<FloatValue?>())`: stops need values.
 * - `CircleLayer(radius = feature["r"].cast<DpValue?>())`: a numeric property needs a value.
 */
class NullabilityTest {

  @Test
  fun feature_data_is_nullable_and_untyped() {
    val property: Expression<AnyValue?> = feature["name"]
    val state: Expression<AnyValue?> = feature.state("hover")
    val id: Expression<AnyValue?> = feature.id()
    val nested: Expression<AnyValue?> = feature.properties()["name"]
    val item: Expression<AnyValue?> = feature["tags"].asList()[0]
    assertEquals("""["get","name"]""", styleJson(property))
    assertEquals("""["feature-state","hover"]""", styleJson(state))
    assertEquals("""["id"]""", styleJson(id))
    assertEquals("""["get","name",["properties"]]""", styleJson(nested))
    assertEquals("""["at",0,["array",["get","tags"]]]""", styleJson(item))
  }

  @Test
  fun a_value_is_assignable_to_its_nullable_type() {
    val string: Expression<StringValue> = const("a")
    val nullable: Expression<StringValue?> = string
    val equatable: Expression<EquatableValue?> = nullable
    val star: Expression<*> = nil()
    assertEquals("\"a\"", styleJson(equatable))
    assertEquals("null", styleJson(star))
  }

  @Test
  fun a_null_literal_fits_only_where_the_map_accepts_it() {
    val literal: Expression<NullValue> = nil()
    assertEquals("null", styleJson(literal))
    assertEquals("""["==",["get","name"],null]""", styleJson(feature["name"] eq nil()))
    assertEquals(
      """["!=",null,["feature-state","hover"]]""",
      styleJson(nil() neq feature.state("hover")),
    )
    assertEquals("""["literal",["a",null]]""", styleJson(const(listOf(const("a"), nil()))))
    assertEquals(
      """["let","n",null,["==",["var","n"],["get","x"]]]""",
      styleJson(withVariable("n", nil()) { n -> n.use() eq feature["x"] }),
    )
  }

  @Test
  fun a_nullable_expression_flows_where_the_map_handles_null() {
    val name: Expression<StringValue?> = feature["name"].cast()
    assertEquals("""["==",["get","name"],"x"]""", styleJson(name eq const("x")))
    assertEquals(
      """["in",["get","name"],["literal",["a","b"]]]""",
      styleJson(const(listOf("a", "b")).contains(name)),
    )
    assertEquals(
      """["match",["get","name"],"a",1,0]""",
      styleJson(switch(name, case("a", const(1f)), fallback = const(0f))),
    )
    assertEquals("""["typeof",["get","name"]]""", styleJson(name.type()))
    assertEquals("""["to-string",["get","name"]]""", styleJson(name.convertToString()))
    assertEquals("""["format",["get","name"],{}]""", styleJson(format(span(name))))

    // A text or image property accepts a nullable expression and renders null as nothing.
    val label: Expression<FormattedValue?> = name
    val icon: Expression<ImageValue?> = feature["icon"].cast()
    assertEquals("""["get","name"]""", styleJson(label))
    assertEquals("""["get","icon"]""", styleJson(icon))

    // An image named by a string is null when the style lacks it, so images coalesce.
    val named: Expression<ImageValue?> = image(feature["icon"].asString())
    assertEquals(
      """["coalesce",["image",["string",["get","icon"]]],["image","dot"]]""",
      styleJson(coalesce(named, image("dot"))),
    )

    val rank: Expression<FloatValue?> = feature["rank"].cast()
    val ranked: Expression<FloatValue> =
      withVariable("r", rank) { r -> coalesce(r.use(), fallback = const(0f)) }
    assertEquals("""["let","r",["get","rank"],["coalesce",["var","r"],0]]""", styleJson(ranked))
  }

  @Test
  fun a_nullable_expression_becomes_a_value_through_coalesce_or_an_assertion() {
    val name: Expression<StringValue?> = feature["name"].cast()
    val withFallback: Expression<StringValue> =
      coalesce(name, feature["name:en"].cast(), fallback = const("?"))
    assertEquals("""["coalesce",["get","name"],["get","name:en"],"?"]""", styleJson(withFallback))

    val stillNullable: Expression<StringValue?> = coalesce(name, feature["name:en"].cast())
    assertEquals("""["coalesce",["get","name"],["get","name:en"]]""", styleJson(stillNullable))

    val asserted: Expression<StringValue> = name.asString(const(""))
    assertEquals("""["upcase",["string",["get","name"],""]]""", styleJson(asserted.uppercase()))

    val converted: Expression<FloatValue> = feature["rank"].convertToNumber(const(0f))
    assertEquals("""["to-number",["get","rank"],0]""", styleJson(converted))
  }

  @Test
  fun decision_outputs_are_nullable_when_any_branch_is() {
    val maybe: Expression<StringValue?> =
      switch(
        condition(feature.has("a"), feature["a"].cast<StringValue?>()),
        fallback = const("none"),
      )
    assertEquals("""["case",["has","a"],["get","a"],"none"]""", styleJson(maybe))

    val certain: Expression<StringValue> =
      switch(condition(feature.has("a"), feature["a"].asString()), fallback = const("none"))
    assertEquals("""["case",["has","a"],["string",["get","a"]],"none"]""", styleJson(certain))

    val stepped: Expression<AnyValue?> = step(zoom(), feature["low"], 10 to feature["high"])
    assertEquals("""["step",["zoom"],["get","low"],10,["get","high"]]""", styleJson(stepped))

    val matched: Expression<FloatValue?> =
      switch(feature["kind"], case("a", const(1f)), fallback = feature["n"].cast<FloatValue?>())
    assertEquals("""["match",["get","kind"],"a",1,["get","n"]]""", styleJson(matched))
  }
}
