package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class LayerHandlePropertyTest {
  @Test
  fun a_layer_handle_updates_and_reads_a_paint_property(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      val handle = assertNotNull(fixture.state.style.layers["background"])

      handle.setPaintProperty("background-opacity", JsonPrimitive(0.25))

      assertEquals(JsonPrimitive(0.25), handle.getProperty("background-opacity"))
    }
  }

  /**
   * The typed pair folds the engines' two cleared shapes into one answer: MapLibre Native reports
   * nothing for a cleared transition and MapLibre GL JS reports an empty object.
   */
  @Test
  fun a_layer_handle_writes_and_reads_a_typed_paint_transition(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      val handle = assertNotNull(fixture.state.style.layers["background"])
      val timing = TransitionOptions(700.milliseconds, 50.milliseconds)

      handle.setPaintTransition("background-color", timing)

      assertEquals(timing, handle.getPaintTransition("background-color"))

      handle.setPaintTransition("background-color", null)

      assertNull(handle.getPaintTransition("background-color"))
    }
  }

  @Test
  fun a_rejected_layer_property_has_a_public_handle_error(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      val handle = assertNotNull(fixture.state.style.layers["background"])

      assertFailsWith<StyleHandleException> {
        handle.setPaintProperty("not-a-style-property", JsonPrimitive(0.25))
      }
    }
  }

  @Test
  fun root_properties_are_readable_and_rejected_writes_throw(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      val handle = assertNotNull(fixture.state.style.layers["background"])
      val circle = assertNotNull(fixture.state.style.layers["points"])

      assertEquals(JsonPrimitive("background"), handle.getProperty("id"))
      assertEquals(JsonPrimitive("background"), handle.getProperty("type"))
      assertEquals(JsonPrimitive(2.0), handle.getProperty("minzoom"))
      assertEquals(JsonPrimitive("points-source"), circle.getProperty("source"))
      handle.setRootProperty("minzoom", JsonPrimitive(3.0))
      assertEquals(JsonPrimitive(3.0), handle.getProperty("minzoom"))
      assertFailsWith<StyleHandleException> {
        handle.setRootProperty("minzoom", JsonPrimitive("4"))
      }
      assertFailsWith<StyleHandleException> {
        handle.setRootProperty("source-layer", JsonPrimitive("replacement"))
      }
    }
  }

  private companion object {
    val STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{"points-source":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}},"layers":[{"id":"background","type":"background","minzoom":2},{"id":"points","type":"circle","source":"points-source"}]}"""
      )
  }
}
