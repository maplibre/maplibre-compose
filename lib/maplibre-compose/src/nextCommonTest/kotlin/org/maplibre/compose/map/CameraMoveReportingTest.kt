package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

/**
 * A camera move means the gesture, not the jump. MapLibre's own events are per change, so reported
 * literally `isCameraMoving` flickers between two Compose frames and a reader never sees it.
 */
class CameraMoveReportingTest {

  @Test
  fun a_drag_reports_one_move_spanning_the_whole_gesture(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtRest()

      val token = it.gestures.onGestureStarted()
      repeat(DRAG_SAMPLES) { _ ->
        it.gestures.moveBy(DRAG_STEP_DP, DRAG_STEP_DP, gestureToken = token)
        it.pump(FRAMES_PER_SAMPLE)
      }
      // A drag ends with the map at rest, which is when nothing else will report a camera change.
      it.settle()
      it.gestures.onGestureEnded(token)
      it.pump(FRAMES_PER_SAMPLE)

      val events = it.events.toList()
      assertEquals(
        listOf("cameraMoveStarted(GESTURE)"),
        events.filter { event -> event.startsWith("cameraMoveStarted") },
        "A drag should report starting once, as one gesture rather than one jump per pointer " +
          "sample. Got: $events",
      )
      assertEquals(
        1,
        events.count { event -> event == "cameraMoveEnded" },
        "A drag should report ending once, after the gesture. Got: $events",
      )
      assertEquals(
        events.last(),
        "cameraMoveEnded",
        "The move should end when the gesture does, not while it is still running. Got: $events",
      )
      assertTrue(
        events.indexOf("cameraMoved") > 0,
        "The drag should still report the camera moving in between. Got: $events",
      )
    }
  }

  /** Nothing brackets a programmatic change, so a move suppressed here would never end. */
  @Test
  fun a_programmatic_move_reports_a_complete_move_on_its_own(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtRest()

      it.gestures.moveBy(DRAG_STEP_DP, DRAG_STEP_DP)
      it.pump(FRAMES_PER_SAMPLE)

      val events = it.events.toList()
      assertEquals(
        listOf("cameraMoveStarted(PROGRAMMATIC)"),
        events.filter { event -> event.startsWith("cameraMoveStarted") },
        "Got: $events",
      )
      assertEquals(1, events.count { event -> event == "cameraMoveEnded" }, "Got: $events")
    }
  }

  @Test
  fun closing_during_a_gesture_ends_the_camera_move(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtRest()

      val token = it.gestures.onGestureStarted()
      it.gestures.moveBy(DRAG_STEP_DP, DRAG_STEP_DP, gestureToken = token)
      it.pump(FRAMES_PER_SAMPLE)
      it.closeSession()
      it.closeSession()

      val events = it.events.toList()
      assertEquals(
        1,
        events.count { event -> event.startsWith("cameraMoveStarted") },
        "the gesture should start one move: $events",
      )
      assertEquals(
        1,
        events.count { event -> event == "cameraMoveEnded" },
        "terminal teardown should end that move exactly once: $events",
      )
    }
  }

  private suspend fun MapFixture.startAtRest() {
    loadStyle(BaseStyle.Empty)
    awaitMapReady()
    settle()
    events.clear()
  }

  private companion object {
    /** Enough pointer samples that a per-jump report would be unmistakable. */
    const val DRAG_SAMPLES = 4

    const val DRAG_STEP_DP = 10.0

    /** Frames between samples, so each jump's events are drained before the next. */
    const val FRAMES_PER_SAMPLE = 4
  }
}
