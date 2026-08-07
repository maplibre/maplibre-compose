package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle

/**
 * Pins what a camera move means to a consumer: the gesture, not the jump.
 *
 * MapLibre's own camera events are per change, and a drag is many changes. Reported literally,
 * `isCameraMoving` flickers true and false between two Compose frames, so a reader that only sees
 * the value at recomposition never observes the gesture. Android and iOS report one move per
 * gesture, which is what the common API means.
 */
class MlnFfiCameraMoveReportingTest {

  @Test
  fun a_drag_reports_one_move_spanning_the_whole_gesture() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      fixture.settle()
      fixture.events.clear()

      fixture.session.onGestureStarted()
      repeat(DRAG_SAMPLES) {
        fixture.session.moveBy(DRAG_STEP_DP, DRAG_STEP_DP)
        fixture.pump(FRAMES_PER_SAMPLE)
      }
      fixture.session.onGestureEnded()
      fixture.pump(FRAMES_PER_SAMPLE)

      val events = fixture.events.toList()
      assertEquals(
        listOf("cameraMoveStarted(GESTURE)"),
        events.filter { it.startsWith("cameraMoveStarted") },
        "A drag should report starting once, as one gesture rather than one jump per pointer " +
          "sample. Got: $events",
      )
      assertEquals(
        1,
        events.count { it == "cameraMoveEnded" },
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

  /**
   * A programmatic camera change is a single change with nothing bracketing it the way a gesture
   * is, so a move suppressed here would never end at all.
   */
  @Test
  fun a_programmatic_move_reports_a_complete_move_on_its_own() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      fixture.settle()
      fixture.events.clear()

      fixture.session.moveBy(DRAG_STEP_DP, DRAG_STEP_DP)
      fixture.pump(FRAMES_PER_SAMPLE)

      val events = fixture.events.toList()
      assertEquals(
        listOf("cameraMoveStarted(PROGRAMMATIC)"),
        events.filter { it.startsWith("cameraMoveStarted") },
        "Got: $events",
      )
      assertEquals(1, events.count { it == "cameraMoveEnded" }, "Got: $events")
    }
  }

  private companion object {
    /** Enough pointer samples that a per-jump report would be unmistakable. */
    const val DRAG_SAMPLES = 4

    const val DRAG_STEP_DP = 10.0

    /** Frames between samples, so each jump's events are drained before the next. */
    const val FRAMES_PER_SAMPLE = 4
  }
}
