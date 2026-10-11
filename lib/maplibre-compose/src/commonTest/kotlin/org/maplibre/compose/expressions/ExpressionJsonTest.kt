package org.maplibre.compose.expressions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.call
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.options
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.dsl.verbatim
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.util.ImageStretch

class ExpressionJsonTest {
  @Test
  fun export_needs_no_map_and_preserves_filters_literals_and_raw_arguments() {
    assertEquals(
      "[\"==\",[\"string\",[\"get\",\"kind\"]],\"park\"]",
      (feature["kind"].asString() eq const("park")).toStyleJson(),
    )
    assertEquals("[\"literal\",[\"get\",\"kind\"]]", const(listOf("get", "kind")).toStyleJson())
    val expression =
      call<StringValue>(
        "custom",
        options("locale" to const("en")),
        verbatim(Json.parseToJsonElement("{\"value\":null}")),
      )
    assertEquals(
      Json.parseToJsonElement("[\"custom\",{\"locale\":\"en\"},{\"value\":null}]"),
      Json.parseToJsonElement(expression.toStyleJson()),
    )
    assertEquals("[\"image\",\"marker\"]", image("marker").toStyleJson())
  }

  @Test
  fun scales_are_explicit_and_required_only_for_referenced_units() {
    assertFailsWith<IllegalArgumentException> { const(2.em).toStyleJson() }
    assertFailsWith<IllegalArgumentException> { const(2.sp).toStyleJson() }
    assertFailsWith<IllegalArgumentException> { textOffset(2.dp, 4.dp).toStyleJson() }
    val options = ExpressionJsonOptions {
      emScale = const(8f)
      spScale = const(1.5f)
      dpScale = const(0.25f)
    }
    assertEquals(16f, Json.parseToJsonElement(const(2.em).toStyleJson(options)).jsonPrimitive.float)
    assertEquals(3f, Json.parseToJsonElement(const(2.sp).toStyleJson(options)).jsonPrimitive.float)
    assertEquals(2f, Json.parseToJsonElement(const(2.dp).toStyleJson()).jsonPrimitive.float)
    val offset = Json.parseToJsonElement(textOffset(2.dp, 4.dp).toStyleJson(options)) as JsonArray
    val coordinates = offset[1] as JsonArray
    assertEquals(0.5f, coordinates[0].jsonPrimitive.float)
    assertEquals(1f, coordinates[1].jsonPrimitive.float)
    val changed = ExpressionJsonOptions(from = options) { emScale = feature["size"].asNumber() }
    assertSame(options.spScale, changed.spScale)
    val scaled = Json.parseToJsonElement(const(2.em).toStyleJson(changed)) as JsonArray
    assertEquals("*", scaled[0].jsonPrimitive.content)
    assertEquals(2f, scaled[1].jsonPrimitive.float)
    assertEquals(Json.parseToJsonElement("[\"number\",[\"get\",\"size\"]]"), scaled[2])
  }

  @Test
  fun image_resolver_receives_inputs_once_per_reference_without_rendering() {
    val bitmap = FakeImageBitmap(16, 16)
    val painter = ColorPainter(Color.Red)
    val size = DpSize(20.dp, 24.dp)
    val stretch = ImageStretch.capInsets(1.dp, 2.dp, 3.dp, 4.dp)
    val filter = ColorFilter.tint(Color.Blue)
    val references = mutableListOf<ExpressionImageReference>()
    val options = ExpressionJsonOptions {
      imageResolver = ExpressionImageResolver { reference ->
        references += reference
        when (reference) {
          is ExpressionImageReference.Bitmap -> "bitmap"
          is ExpressionImageReference.Painter -> "painter"
          else -> null
        }
      }
    }
    val bitmapExpression = image(bitmap, isSdf = true, stretch = stretch)
    val painterExpression =
      image(painter, size, drawAsSdf = true, stretch = stretch, alpha = 0.5f, colorFilter = filter)
    val expression =
      call<StringValue>("custom", bitmapExpression, bitmapExpression, painterExpression)
    assertEquals(
      "[\"custom\",[\"image\",\"bitmap\"],[\"image\",\"bitmap\"],[\"image\",\"painter\"]]",
      expression.toStyleJson(options),
    )
    assertEquals(2, references.size)
    val bitmapReference = references[0] as ExpressionImageReference.Bitmap
    assertSame(bitmap, bitmapReference.bitmap)
    assertTrue(bitmapReference.isSdf)
    assertSame(stretch, bitmapReference.stretch)
    assertEquals(0, bitmap.reads)
    val painterReference = references[1] as ExpressionImageReference.Painter
    assertSame(painter, painterReference.painter)
    assertEquals(size, painterReference.size)
    assertTrue(painterReference.isSdf)
    assertSame(stretch, painterReference.stretch)
    assertEquals(0.5f, painterReference.alpha)
    assertSame(filter, painterReference.colorFilter)
    expression.toStyleJson(options)
    assertEquals(4, references.size)
    assertFailsWith<IllegalArgumentException> { bitmapExpression.toStyleJson() }
    assertFailsWith<IllegalArgumentException> {
      bitmapExpression.toStyleJson(
        ExpressionJsonOptions { imageResolver = ExpressionImageResolver { null } }
      )
    }
    val failure = IllegalStateException("resolver failed")
    assertSame(
      failure,
      assertFailsWith<IllegalStateException> {
        bitmapExpression.toStyleJson(
          ExpressionJsonOptions { imageResolver = ExpressionImageResolver { throw failure } }
        )
      },
    )
  }

  @Test
  fun non_finite_verbatim_numbers_fail_instead_of_producing_invalid_json() {
    listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { number ->
      assertFailsWith<IllegalArgumentException> {
        call<StringValue>("custom", verbatim(JsonPrimitive(number))).toStyleJson()
      }
    }
  }
}
