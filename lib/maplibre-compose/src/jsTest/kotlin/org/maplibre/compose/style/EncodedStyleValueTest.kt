package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.call
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.verbatim
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.style.internal.StyleValue

class EncodedStyleValueTest {
  @Test
  fun float_negative_zero_matches_the_previous_json_transport() {
    val encoded = StyleValue.Expression(const(-0f).compile(ExpressionContext.None))
    val value = encoded.encoded.toJsValue<dynamic>()
    assertFalse(js("Object.is(value, -0)") as Boolean)
  }

  @Test
  fun object_keys_cannot_modify_the_javascript_prototype() {
    val json =
      Json.parseToJsonElement("""{"__proto__":{"polluted":true},"constructor":"value"}""")
        .jsonObject
    val value = StyleValue.Json(json).encoded.toJsValue<dynamic>()
    assertEquals(null, js("Object.getPrototypeOf(value)"))
    assertEquals(true, value["__proto__"].polluted as Boolean)
    assertEquals("value", value["constructor"] as String)
    assertEquals(json, StyleValue.Json(json).encoded.toJsonElement())
  }

  @Test
  fun verbatim_signed_zero_is_preserved_and_compares_as_a_distinct_engine_value() {
    val negative =
      StyleValue.Expression(
        call<StringValue>("custom", verbatim(Json.parseToJsonElement("-0.0")))
          .compile(ExpressionContext.None)
      )
    val positive =
      StyleValue.Expression(
        call<StringValue>("custom", verbatim(Json.parseToJsonElement("0.0")))
          .compile(ExpressionContext.None)
      )
    val value: dynamic = negative.encoded.toJsValue<dynamic>()
    assertTrue(js("Object.is(value[1], -0)") as Boolean)
    assertFalse(negative == positive)
  }
}
