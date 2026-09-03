package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
  fun a_backlogged_owner_thread_orders_newer_gesture_tokens_and_ignores_stale_ends():
    MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val session = fixture.session as MlnFfiMapSession
      fixture.loadStyle(BaseStyle.Empty)
      fixture.awaitMapReady()
      // Zoomed in, so the world is taller than the viewport and a vertical pan is not constrained.
      fixture.state.setCameraPosition(CameraPosition(zoom = START_ZOOM))
      fixture.pumpUntil("the camera to adopt the start zoom") {
        abs(session.getCameraPosition().zoom - START_ZOOM) < ZOOM_TOLERANCE
      }
      fixture.settle()
      fixture.events.clear()
      val start = session.getCameraPosition()

      val entered = TestLatch(1)
      val release = TestLatch(1)
      assertTrue(
        session.postOwnerTaskForTest {
          entered.countDown()
          check(release.await(5_000))
        }
      )
      assertTrue(entered.await(5_000))

      val stale = fixture.gestures.onGestureStarted()
      fixture.gestures.moveBy(DRAG_STEP_DP, 0.0, gestureToken = stale)
      val latest = fixture.gestures.onGestureStarted()
      fixture.gestures.moveBy(0.0, DRAG_STEP_DP, gestureToken = latest)
      fixture.gestures.onGestureEnded(latest)
      fixture.gestures.onGestureEnded(stale)
      release.countDown()
      fixture.pump(FRAMES)
      fixture.settle()

      val camera = session.getCameraPosition()
      assertTrue(
        abs(camera.target.longitude - start.target.longitude) > MIN_DELTA_DEGREES &&
          abs(camera.target.latitude - start.target.latitude) > MIN_DELTA_DEGREES,
        "both queued deltas should have reached the camera: $start then $camera",
      )
      assertEquals(CameraMoveReason.GESTURE, fixture.state.cameraMoveReason)
      assertFalse(fixture.state.isCameraMoving)

      val gestures = fixture.events.filter { it.startsWith("gesture(") }
      assertEquals(
        1,
        gestures.count { it == "gesture(false)" },
        "the gesture ended more than once: $gestures",
      )
    }
  }

  private companion object {
    const val DRAG_STEP_DP = 10.0

    const val FRAMES = 8

    const val START_ZOOM = 3.0

    const val ZOOM_TOLERANCE = 0.01

    const val MIN_DELTA_DEGREES = 0.5
  }
}
