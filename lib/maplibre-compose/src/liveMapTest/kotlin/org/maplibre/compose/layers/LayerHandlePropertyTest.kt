package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.captureWarnings
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

      assertEquals(
        timing.scaledBy(systemAnimatorDurationScale()),
        handle.getPaintTransition("background-color"),
      )

      handle.setPaintTransition("background-color", null)

      assertNull(handle.getPaintTransition("background-color"))
    }
  }

  /** A posted write returns before the engine sees it, so its rejection arrives as a warning. */
  @Test
  fun a_rejected_layer_property_is_logged_and_keeps_the_previous_value(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(STYLE)
        val handle = assertNotNull(fixture.state.style.layers["background"])
        handle.setPaintProperty("background-opacity", JsonPrimitive(0.25))

        captureWarnings { warnings ->
          handle.setPaintProperty("background-opacity", JsonPrimitive("opaque"))

          // The read is queued behind the write, so the warning has been logged once it answers.
          assertEquals(JsonPrimitive(0.25), handle.getProperty("background-opacity"))
          assertTrue(
            warnings.any { "'background'" in it && "'background-opacity'" in it },
            "Expected a rejection warning, got $warnings",
          )
        }
      }
    }

  @Test
  fun root_properties_are_readable_and_rejected_writes_are_logged(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      val handle = assertNotNull(fixture.state.style.layers["background"])
      val circle = assertNotNull(fixture.state.style.layers["points"])

      assertEquals("background", handle.type)
      assertNull(handle.source)
      assertNull(handle.sourceLayer)
      assertEquals("circle", circle.type)
      assertEquals("points-source", circle.source)
      assertEquals("points-layer", circle.sourceLayer)
      assertEquals(JsonPrimitive("background"), handle.getProperty("id"))
      assertEquals(JsonPrimitive("background"), handle.getProperty("type"))
      assertEquals(JsonPrimitive(2.0), handle.getProperty("minzoom"))
      assertEquals(JsonPrimitive("points-source"), circle.getProperty("source"))
      assertEquals(JsonPrimitive("points-layer"), circle.getProperty("source-layer"))
      handle.setRootProperty("minzoom", JsonPrimitive(3.0))
      assertEquals(JsonPrimitive(3.0), handle.getProperty("minzoom"))

      captureWarnings { warnings ->
        handle.setRootProperty("minzoom", JsonPrimitive("4"))
        handle.setRootProperty("source-layer", JsonPrimitive("replacement"))
        assertEquals(JsonPrimitive(3.0), handle.getProperty("minzoom"))
        assertNull(handle.getProperty("source-layer"))
        assertEquals(2, warnings.count { "'background'" in it }, "Warnings: $warnings")
      }
    }
  }

  private companion object {
    val STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{"points-source":{"type":"vector","tiles":["https://example.invalid/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"background","type":"background","minzoom":2},{"id":"points","type":"circle","source":"points-source","source-layer":"points-layer"}]}"""
      )
  }
}
