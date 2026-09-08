package org.maplibre.compose.expressions.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.util.toStyleJson

/**
 * A feature property has no known type until the map evaluates it. MapLibre accepts it in equality
 * comparisons without an assertion, so the DSL does too.
 */
class AnyValueTest {

  private fun json(expression: Expression<*>): String =
    expression.compile(ExpressionContext.None).toStyleJson().toString()

  @Test
  fun property_compares_equal_to_a_constant_without_an_assertion() {
    assertEquals("""["==",["get","oneway"],"yes"]""", json(feature["oneway"] eq const("yes")))
    assertEquals("""["!=",true,["get","oneway"]]""", json(const(true) neq feature["oneway"]))
  }

  @Test
  fun property_compares_equal_to_another_property() {
    assertEquals("""["==",["get","a"],["get","b"]]""", json(feature["a"] eq feature["b"]))
  }

  @Test
  fun property_compares_equal_to_null() {
    assertEquals("""["==",["get","name"],null]""", json(feature["name"] eq nil()))
  }

  @Test
  fun feature_state_and_id_compare_without_an_assertion() {
    assertEquals(
      """["==",["feature-state","selected"],true]""",
      json(feature.state("selected") eq const(true)),
    )
    assertEquals("""["==",["id"],"a"]""", json(feature.id() eq const("a")))
  }

  @Test
  fun switch_matches_a_property_against_labels_of_one_type() {
    assertEquals(
      """["match",["get","kind"],"park","green",["road","path"],"gray","none"]""",
      json(
        switch(
          input = feature["kind"],
          case("park", const("green")),
          case(listOf("road", "path"), const("gray")),
          fallback = const("none"),
        )
      ),
    )
  }

  @Test
  fun list_membership_accepts_a_property() {
    assertEquals(
      """["in",["get","oneway"],["literal",["yes","-1"]]]""",
      json(const(listOf("yes", "-1")).contains(feature["oneway"])),
    )
  }

  @Test
  fun properties_object_and_asserted_collections_yield_untyped_items() {
    assertEquals(
      """["==",["get","kind",["properties"]],"park"]""",
      json(feature.properties()["kind"] eq const("park")),
    )
    assertEquals(
      """["in","a",["array",["get","tags"]]]""",
      json(feature["tags"].asList().contains(const("a"))),
    )
  }

  @Test
  fun property_takes_a_known_type_through_assertion_conversion_or_cast() {
    // The declared types are the contract; the compiler checks them.
    val asserted: Expression<StringValue> = feature["name"].asString()
    val converted: Expression<ColorValue> = feature["color"].convertToColor()
    val assumed: Expression<ColorValue> = feature["color"].cast()
    assertEquals("""["string",["get","name"]]""", json(asserted))
    assertEquals("""["to-color",["get","color"]]""", json(converted))
    assertEquals("""["get","color"]""", json(assumed))
  }
}
