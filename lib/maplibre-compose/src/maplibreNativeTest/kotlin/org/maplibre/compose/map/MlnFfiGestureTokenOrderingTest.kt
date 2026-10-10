package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

/** The rest of the camera-move contract is in [CameraMoveReportingTest], on every platform. */
class MlnFfiGestureTokenOrderingTest {

  @Test
  fun a_backlogged_event_pump_reports_one_gesture_end_for_replaced_gestures(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          val session = fixture.session as MlnFfiMapSession
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          // Zoomed in, so the world is taller than the viewport and a vertical pan is not
          // constrained.
          fixture.state.setCameraPosition(CameraPosition(zoom = StartZoom))
          fixture.pumpUntil("the camera to adopt the start zoom") {
            abs(session.getCameraPosition().zoom - StartZoom) < ZoomTolerance
          }
          fixture.settle()
          fixture.events.clear()
          val start = session.getCameraPosition()

          val entered = TestLatch(1)
          val release = TestLatch(1)
          session.holdEventPump(onHeld = { entered.countDown() }) { check(release.await(5_000)) }

          try {
            assertTrue(entered.await(5_000))

            val stale = fixture.gestures.onGestureStarted()
            fixture.gestures.moveBy(DragStepDp, 0.0, gestureToken = stale)
            val latest = fixture.gestures.onGestureStarted()
            fixture.gestures.moveBy(0.0, DragStepDp, gestureToken = latest)
            fixture.gestures.onGestureEnded(latest)
            fixture.gestures.onGestureEnded(stale)
            release.countDown()
            fixture.awaitWhileRendering("the current gesture to finish") {
              latest.awaitCompletion()
            }
          } finally {
            release.countDown()
          }

          // Each delta went to native while its gesture owned the camera, so both moved it.
          val camera = session.getCameraPosition()
          assertTrue(
            abs(camera.center.longitude - start.center.longitude) > MinDeltaDegrees &&
              abs(camera.center.latitude - start.center.latitude) > MinDeltaDegrees,
            "each gesture's delta should reach the camera: $start then $camera",
          )
          assertEquals(
            CameraMoveReason.Gesture,
            fixture.state.cameraMoveReason,
            fixture.events.toString(),
          )
          assertFalse(fixture.state.isCameraMoving)

          val gestures = fixture.events.filter { it.startsWith("gesture(") }
          assertEquals(
            1,
            gestures.count { it == "gesture(false)" },
            "the gesture ended more than once: $gestures",
          )
        }
      }
    }

  private companion object {
    const val DragStepDp = 10.0

    const val StartZoom = 3.0

    const val ZoomTolerance = 0.01

    const val MinDeltaDegrees = 0.5
  }
}
