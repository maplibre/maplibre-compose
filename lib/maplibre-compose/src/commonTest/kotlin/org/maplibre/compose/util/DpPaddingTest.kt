package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DpPaddingTest {
  @Test
  fun physical_edges_are_independent_of_layout_direction() {
    val padding: PaddingValues = DpPadding(1.dp, 2.dp, 3.dp, 4.dp)

    for (direction in LayoutDirection.entries) {
      assertEquals(1.dp, padding.calculateLeftPadding(direction))
      assertEquals(2.dp, padding.calculateTopPadding())
      assertEquals(3.dp, padding.calculateRightPadding(direction))
      assertEquals(4.dp, padding.calculateBottomPadding())
    }
  }

  @Test
  fun directional_padding_resolves_start_and_end_to_physical_edges() {
    val padding = PaddingValues(start = 1.dp, top = 2.dp, end = 3.dp, bottom = 4.dp)

    assertEquals(DpPadding(1.dp, 2.dp, 3.dp, 4.dp), padding.toDpPadding(LayoutDirection.Ltr))
    assertEquals(DpPadding(3.dp, 2.dp, 1.dp, 4.dp), padding.toDpPadding(LayoutDirection.Rtl))
  }

  @Test
  fun padding_values_interoperability_preserves_the_saved_format() {
    val saved = """{"left":1.0,"top":2.0,"right":3.0,"bottom":4.0}"""
    val padding = Json.decodeFromString<DpPadding>(saved)

    assertEquals(DpPadding(1.dp, 2.dp, 3.dp, 4.dp), padding)
    val encoded = Json.parseToJsonElement(Json.encodeToString(padding)).jsonObject
    assertFalse(encoded.values.any { it.jsonPrimitive.isString })
    assertEquals(
      mapOf("left" to 1f, "top" to 2f, "right" to 3f, "bottom" to 4f),
      encoded.mapValues { (_, value) -> value.jsonPrimitive.float },
    )
  }
}
