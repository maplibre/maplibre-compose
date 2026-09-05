package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
  fun a_backlogged_owner_rejects_stale_commands_and_ignores_stale_ends(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          val session = fixture.session as MlnFfiMapSession
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          // Zoomed in, so the world is taller than the viewport and a vertical pan is not
          // constrained.
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
            abs(camera.target.longitude - start.target.longitude) < ZOOM_TOLERANCE &&
              abs(camera.target.latitude - start.target.latitude) > MIN_DELTA_DEGREES,
            "only the current owner's delta should reach the camera: $start then $camera",
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
    }

  @Test
  fun normal_scope_completion_waits_for_a_backlogged_owner_fence(): MapTestResult = runMapTest {
    coroutineScope {
      createMapFixture().use { fixture ->
        val session = fixture.session as MlnFfiMapSession
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        fixture.state.setCameraPosition(CameraPosition(zoom = START_ZOOM))
        fixture.settle()
        val before = fixture.state.cameraPosition
        val entered = TestLatch(1)
        val release = TestLatch(1)
        assertTrue(
          session.postOwnerTaskForTest {
            entered.countDown()
            check(release.await(5_000))
          }
        )
        try {
          assertTrue(entered.await(5_000))
          val queued = CompletableDeferred<Unit>()
          val work =
            async(start = CoroutineStart.UNDISPATCHED) {
              fixture.state.gestureCamera.withGesture {
                moveBy(DRAG_STEP_DP, 0.0)
                moveBy(DRAG_STEP_DP, 0.0)
                queued.complete(Unit)
              }
            }
          queued.await()
          assertFalse(work.isCompleted, "scope returned before its queued work executed")
          release.countDown()
          fixture.awaitWhileRendering("normal gesture completion fence") { work.await() }
          assertTrue(
            abs(fixture.state.cameraPosition.target.longitude - before.target.longitude) >
              MIN_DELTA_DEGREES
          )
          assertFalse(fixture.state.isCameraMoving)
        } finally {
          release.countDown()
        }
      }
    }
  }

  @Test
  fun caller_cancellation_drops_queued_scope_commands(): MapTestResult = runMapTest {
    coroutineScope {
      createMapFixture().use { fixture ->
        val session = fixture.session as MlnFfiMapSession
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        fixture.state.setCameraPosition(CameraPosition(zoom = START_ZOOM))
        fixture.settle()
        val before = fixture.state.cameraPosition
        val entered = TestLatch(1)
        val release = TestLatch(1)
        assertTrue(
          session.postOwnerTaskForTest {
            entered.countDown()
            check(release.await(5_000))
          }
        )
        try {
          assertTrue(entered.await(5_000))
          val queued = CompletableDeferred<Unit>()
          val work =
            launch(start = CoroutineStart.UNDISPATCHED) {
              fixture.state.gestureCamera.withGesture {
                moveBy(DRAG_STEP_DP, 0.0)
                queued.complete(Unit)
                awaitCancellation()
              }
            }
          queued.await()
          work.cancel()
          release.countDown()
          fixture.awaitWhileRendering("cancelled gesture completion fence") { work.join() }
          fixture.settle()
          assertTrue(work.isCancelled)
          assertEquals(before.target.longitude, fixture.state.cameraPosition.target.longitude, 1e-6)
          assertFalse(fixture.state.isCameraMoving)
        } finally {
          release.countDown()
        }
      }
    }
  }

  @Test
  fun a_new_gesture_rejects_a_queued_public_camera_set(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val session = fixture.session as MlnFfiMapSession
      fixture.loadStyle(BaseStyle.Empty)
      fixture.awaitMapReady()
      fixture.state.setCameraPosition(CameraPosition(zoom = START_ZOOM))
      fixture.settle()
      val entered = TestLatch(1)
      val release = TestLatch(1)
      assertTrue(
        session.postOwnerTaskForTest {
          entered.countDown()
          check(release.await(5_000))
        }
      )
      try {
        assertTrue(entered.await(5_000))
        fixture.state.setCameraPosition(CameraPosition(zoom = 12.0))
        val gesture = fixture.gestures.onGestureStarted()
        fixture.gestures.moveBy(DRAG_STEP_DP, 0.0, gestureToken = gesture)
        fixture.gestures.onGestureEnded(gesture)
        release.countDown()
        fixture.settle()
        assertEquals(START_ZOOM, session.getCameraPosition().zoom, 1e-6)
      } finally {
        release.countDown()
      }
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
