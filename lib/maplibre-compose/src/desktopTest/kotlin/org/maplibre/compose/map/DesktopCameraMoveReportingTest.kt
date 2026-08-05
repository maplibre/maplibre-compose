package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle

/**
 * Pins what a camera move means to a consumer: the gesture, not the jump.
 *
 * MapLibre's own camera events are per change, and a drag is many changes — one jump per pointer
 * sample, each with its own will-change and did-change. Reported literally, `isCameraMoving` goes
 * true and false again inside a single drain of the event queue, which is between two Compose
 * frames, and Compose only shows a reader the value at recomposition. A flag that flickers below
 * frame rate reads as permanently false, so a consumer watching for a gesture never sees one. That
 * is not a subtle degradation: the Material 3 attribution button is supposed to collapse when the
 * user moves the map, and it simply did not.
 *
 * Android and iOS report one move per gesture, so this is also what the common API means by it.
 */
class DesktopCameraMoveReportingTest {

  @Test
  fun `a drag reports one move, spanning the whole gesture`() {
    HeadlessMapFixture.create().use { fixture ->
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
   * The other half, and the reason this is not simply "never end a move": a programmatic camera
   * change is one change, so it has to report a complete move on its own. Nothing brackets it the
   * way a gesture is bracketed, so a move suppressed here would never end at all.
   */
  @Test
  fun `a programmatic move reports a complete move on its own`() {
    HeadlessMapFixture.create().use { fixture ->
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
