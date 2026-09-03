package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

/** Both engines translate the events they emit into the same [MapEvent] catalog. */
class EngineEventTest {

  @Test
  fun a_loaded_style_is_reported_once(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)

      assertEquals(1, fixture.engineEvents.count { it is MapEvent.StyleLoaded })
    }
  }

  @Test
  fun an_unreachable_style_reports_a_failure_with_a_reason(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri(UNREACHABLE_STYLE_URI))
      fixture.pumpUntil("the style load to fail") {
        fixture.engineEvents.any { it is MapEvent.StyleLoadFailed }
      }

      val failures = fixture.engineEvents.filterIsInstance<MapEvent.StyleLoadFailed>()
      assertEquals(1, failures.size)
      assertTrue(failures.single().reason.isNotBlank())
    }
  }

  @Test
  fun a_rendered_map_reports_frames_and_an_idle(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.awaitMapReady()
      fixture.settle()

      val frames = fixture.engineEvents.filterIsInstance<MapEvent.FrameRendered>()
      assertTrue(frames.isNotEmpty(), "no frame was reported")
      when (mapLibreFlavor) {
        MapLibreFlavor.NATIVE -> assertNotNull(frames.first().stats)
        MapLibreFlavor.GL_JS -> assertNull(frames.first().stats)
      }
      assertTrue(fixture.engineEvents.any { it is MapEvent.Idle }, "no idle was reported")
    }
  }

  @Test
  fun an_animated_camera_reports_a_complete_move(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.awaitMapReady()
      fixture.settle()
      fixture.engineEvents.clear()

      fixture.awaitWhileRendering("the camera animation to finish") {
        fixture.session.animateCameraPosition(DESTINATION, ANIMATION_DURATION)
      }

      val started = fixture.engineEvents.indexOfFirst { it is MapEvent.CameraMoveStarted }
      val ended = fixture.engineEvents.indexOfLast { it is MapEvent.CameraMoveEnded }
      assertTrue(started >= 0, "no camera move started: ${fixture.engineEvents}")
      assertTrue(ended > started, "no camera move ended after it started: ${fixture.engineEvents}")
      assertTrue(
        fixture.engineEvents.subList(started, ended).any { it is MapEvent.CameraMoved },
        "the move reported no progress: ${fixture.engineEvents}",
      )

      val animated = (fixture.engineEvents[started] as MapEvent.CameraMoveStarted).animated
      when (mapLibreFlavor) {
        MapLibreFlavor.NATIVE -> assertEquals(true, animated)
        MapLibreFlavor.GL_JS -> assertNull(animated)
      }
    }
  }

  @Test
  fun a_missing_icon_is_reported_with_its_id(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Json(MISSING_ICON_STYLE))

      fixture.pumpUntil("the missing icon to be reported", timeout = 20.seconds) {
        MapEvent.StyleImageMissing(MISSING_ICON_ID) in fixture.engineEvents
      }
    }
  }

  private companion object {
    val UNREACHABLE_STYLE_URI: String =
      when (mapLibreFlavor) {
        // The browser resolves a relative path against the Karma server, which answers 404.
        MapLibreFlavor.GL_JS -> "/missing-maplibre-compose-style.json"
        MapLibreFlavor.NATIVE -> "https://example.invalid/style.json"
      }

    const val MISSING_ICON_ID = "missing-icon"

    val MISSING_ICON_STYLE =
      """
      {
        "version": 8,
        "name": "missing icon",
        "sources": {
          "points": {
            "type": "geojson",
            "data": {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [0, 0] },
              "properties": {}
            }
          }
        },
        "layers": [
          {
            "id": "icons",
            "type": "symbol",
            "source": "points",
            "layout": { "icon-image": "$MISSING_ICON_ID", "icon-allow-overlap": true }
          }
        ]
      }
      """
        .trimIndent()

    val DESTINATION = CameraPosition(target = Position(10.0, 10.0), zoom = 4.0)

    val ANIMATION_DURATION = 1.seconds
  }
}
