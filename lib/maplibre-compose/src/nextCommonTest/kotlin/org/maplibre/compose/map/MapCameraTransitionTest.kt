package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * Both backends advance a transition only from inside a render, so every test renders as it waits.
 */
class MapCameraTransitionTest {

  @Test
  fun a_bounds_fit_requested_before_the_first_frame_uses_the_real_viewport(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        // A camera read makes mln-ffi's map creation deterministic without a render target.
        it.session.getCameraPosition()
        it.session.setCameraPosition(
          BOUNDS,
          bearing = 0.0,
          tilt = 0.0,
          padding = PaddingValues(0.dp),
        )
        it.session.getCameraPosition()

        it.awaitMapReady()
        it.pumpUntil("the deferred bounds fit to be applied") {
          it.session.getCameraPosition().zoom > 1.0
        }
        val deferredFit = it.session.getCameraPosition()

        it.session.setCameraPosition(START)
        it.pumpUntil("the camera to reset") {
          abs(it.session.getCameraPosition().zoom - START.zoom) < 0.01
        }
        it.session.setCameraPosition(
          BOUNDS,
          bearing = 0.0,
          tilt = 0.0,
          padding = PaddingValues(0.dp),
        )
        it.pumpUntil("the attached bounds fit to be applied") {
          abs(it.session.getCameraPosition().zoom - START.zoom) > 0.1
        }
        val attachedFit = it.session.getCameraPosition()

        assertNear(attachedFit.zoom, deferredFit.zoom, "the first fit used the startup viewport")
        assertNear(
          attachedFit.target.longitude,
          deferredFit.target.longitude,
          "the first fit chose the wrong longitude",
        )
        assertNear(
          attachedFit.target.latitude,
          deferredFit.target.latitude,
          "the first fit chose the wrong latitude",
        )
      }
    }

  @Test
  fun an_animation_completes_and_lands_on_its_target(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      it.awaitWhileRendering("the animation to complete") {
        it.session.animateCameraPosition(TARGET, 200.milliseconds)
      }

      assertNear(
        TARGET.zoom,
        it.session.getCameraPosition().zoom,
        "the camera should have reached the target zoom",
      )
    }
  }

  /** A zero-duration animation emits its event during the call, so it must not deadlock. */
  @Test
  fun a_zero_duration_animation_completes(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      it.awaitWhileRendering("the instant animation to complete") {
        it.session.animateCameraPosition(TARGET, 0.milliseconds)
      }
    }
  }

  /**
   * Replacing a transition ends the old one, and that end belongs to the transition it replaced.
   */
  @Test
  fun a_replacement_animation_waits_for_its_own_end(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      val superseded =
        CoroutineScope(Dispatchers.Default).launch {
          it.session.animateCameraPosition(TARGET, 10.seconds)
        }
      it.awaitCameraMoving()

      val replacement =
        CoroutineScope(Dispatchers.Default).launch {
          it.session.animateCameraPosition(MIDPOINT, 2.seconds)
        }
      it.pumpUntil("the superseded animation to resume") { superseded.isCompleted }

      assertFalse(
        replacement.isCompleted,
        "the replacement should still be running when the animation it replaced ends",
      )

      it.pumpUntil("the replacement animation to complete") { replacement.isCompleted }
      assertNear(
        MIDPOINT.zoom,
        it.session.getCameraPosition().zoom,
        "the replacement should have reached its own target",
      )
    }
  }

  @Test
  fun a_superseded_animation_resumes_rather_than_hanging(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      val animation: Job =
        CoroutineScope(Dispatchers.Default).launch {
          it.session.animateCameraPosition(TARGET, 10.seconds)
        }
      it.awaitCameraMoving()
      it.session.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 4.0))

      it.pumpUntil("the superseded animation to resume") { animation.isCompleted }
      assertFalse(animation.isCancelled, "a superseded animation should resume, not cancel")
    }
  }

  @Test
  fun cancelling_an_animation_stops_the_camera_and_leaves_nothing_registered(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.startAtOrigin()
        it.events.clear()

        val animation =
          CoroutineScope(Dispatchers.Default).launch {
            it.session.animateCameraPosition(TARGET, 30.seconds)
          }
        it.awaitCameraMoving()
        animation.cancel()
        it.pumpUntil("the cancelled animation to unwind") { animation.isCompleted }

        val stopped = it.session.getCameraPosition()
        assertTrue(
          stopped.zoom < TARGET.zoom - 0.1,
          "the camera should have stopped short of the target, but was $stopped",
        )

        it.awaitWhileRendering("a later animation to complete") {
          it.session.animateCameraPosition(TARGET, 200.milliseconds)
        }
        assertNear(
          TARGET.zoom,
          it.session.getCameraPosition().zoom,
          "a later animation should still complete",
        )
      }
    }

  @Test
  fun closing_the_session_resumes_an_outstanding_animation(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      val animation =
        CoroutineScope(Dispatchers.Default).launch {
          it.session.animateCameraPosition(TARGET, 60.seconds)
        }
      it.awaitCameraMoving()
      it.pumpUntil("the animation's camera move to be reported") {
        it.events.count { event -> event.startsWith("cameraMoveStarted") } >
          it.events.count { event -> event == "cameraMoveEnded" }
      }
      val endedBeforeClose = it.events.count { event -> event == "cameraMoveEnded" }

      it.closeSession()

      it.pumpUntil("the stranded animation to resume") { animation.isCompleted }
      assertFalse(animation.isCancelled, "teardown should resume the waiter, not cancel it")
      assertEquals(
        endedBeforeClose + 1,
        it.events.count { event -> event == "cameraMoveEnded" },
        "teardown should close the outstanding camera move exactly once: ${it.events}",
      )
    }
  }

  private suspend fun MapFixture.startAtOrigin() {
    // GL JS renders nothing without a style.
    loadStyle(BaseStyle.Empty)
    session.setCameraPosition(START)
    // Render first: before the map exists, a camera read echoes back whatever was last set.
    awaitMapReady()
    pumpUntil("the map to reach its starting camera") {
      abs(session.getCameraPosition().zoom - START.zoom) < 0.001
    }
  }

  private suspend fun MapFixture.awaitCameraMoving() {
    pumpUntil("the animation to start moving the camera") {
      abs(session.getCameraPosition().zoom - START.zoom) > 0.01
    }
  }

  private companion object {
    val START = CameraPosition(target = Position(0.0, 0.0), zoom = 2.0)
    val TARGET = CameraPosition(target = Position(11.0, 47.0), zoom = 8.0)
    val MIDPOINT = CameraPosition(target = Position(5.0, 20.0), zoom = 5.0)
    val BOUNDS =
      BoundingBox(
        southwest = Position(longitude = -5.0, latitude = -5.0),
        northeast = Position(longitude = 5.0, latitude = 5.0),
      )

    fun assertNear(expected: Double, actual: Double, message: String) {
      assertTrue(abs(expected - actual) < 0.01, "$message (expected $expected, was $actual)")
    }
  }
}
