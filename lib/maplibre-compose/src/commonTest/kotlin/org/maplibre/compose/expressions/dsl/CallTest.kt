package org.maplibre.compose.expressions.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.StringValue

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

  @Test
  fun verbatim_arguments_are_written_unchanged() {
    val labels = buildJsonArray {
      add("park")
      add("forest")
    }
    val options = buildJsonObject { put("locale", "fr") }
    assertEquals(
      """["custom-op",["get","kind"],["park","forest"],{"locale":"fr"}]""",
      styleJson(
        call<FloatValue>("custom-op", feature["kind"], verbatim(labels), verbatim(options))
      ),
    )
  }

  @Test
  fun options_are_written_as_an_object_of_expressions_without_null_entries() {
    val formatted =
      call<StringValue>(
        "number-format",
        feature["price"].asNumber(),
        options("locale" to feature["locale"].asString(), "currency" to null),
      )
    assertEquals(
      """["number-format",["number",["get","price"]],{"locale":["string",["get","locale"]]}]""",
      styleJson(formatted),
    )
  }
}
