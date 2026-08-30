package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class LayerHandlePropertyTest {
  @Test
  fun a_layer_handle_updates_and_reads_a_paint_property(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      val handle = assertNotNull(fixture.state.style.layer("background"))

      handle.setPaintProperty("background-opacity", JsonPrimitive(0.25))

      assertEquals(JsonPrimitive(0.25), handle.getProperty("background-opacity"))
    }
  }

  @Test
  fun a_rejected_layer_property_has_a_public_handle_error(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      val handle = assertNotNull(fixture.state.style.layer("background"))

      assertFailsWith<StyleHandleException> {
        handle.setPaintProperty("not-a-style-property", JsonPrimitive(0.25))
      }
    }
  }

  private companion object {
    val STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"background","type":"background"}]}"""
      )
  }
}
