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
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * A camera animation completes on MapLibre's own signal rather than on a timer.
 *
 * Every test here renders while it waits: mbgl advances a transition from
 * `onDidFinishRenderingFrame` while `transform.inTransition()`, so one that renders no frames stops
 * after its first step.
 */
class MlnFfiMapCameraTransitionTest {

  @Test
  fun a_camera_requested_before_the_first_frame_survives_the_native_resize() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      val position =
        CameraPosition(
          target = Position(longitude = -122.4194, latitude = 37.7749),
          zoom = 11.0,
          tilt = 35.0,
        )
      it.session.setCameraPosition(position)

      it.pumpUntilRendered()
      it.pumpUntil("the native map to apply the render target size") {
        it.session.hasNativeSizeForTesting(BridgeMapFixture.DEFAULT_EXTENT)
      }
      it.pumpUntil("the deferred camera to be applied after the resize") {
        abs(it.session.getCameraPosition().zoom - position.zoom) < 0.01
      }

      val actual = it.session.getCameraPosition()
      assertNear(position.target.longitude, actual.target.longitude, "longitude changed on resize")
      assertNear(position.target.latitude, actual.target.latitude, "latitude changed on resize")
      assertNear(position.zoom, actual.zoom, "zoom changed on resize")
      assertNear(position.tilt, actual.tilt, "tilt changed on resize")
    }
  }

  @Test
  fun a_bounds_fit_requested_before_the_first_frame_uses_the_real_viewport() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      // An owner-thread read makes map creation deterministic without attaching a render target.
      it.session.getCameraPosition()
      it.session.setCameraPosition(BOUNDS, bearing = 0.0, tilt = 0.0, padding = PaddingValues(0.dp))
      it.session.getCameraPosition()

      it.pumpUntilRendered()
      it.pumpUntil("the deferred bounds fit to be applied") {
        it.session.getCameraPosition().zoom > 1.0
      }
      val deferredFit = it.session.getCameraPosition()

      it.session.setCameraPosition(START)
      it.pumpUntil("the camera to reset") {
        abs(it.session.getCameraPosition().zoom - START.zoom) < 0.01
      }
      it.session.setCameraPosition(BOUNDS, bearing = 0.0, tilt = 0.0, padding = PaddingValues(0.dp))
      it.pumpUntil("the attached bounds fit to be applied") {
        abs(it.session.getCameraPosition().zoom - START.zoom) > 0.1
      }
      val attachedFit = it.session.getCameraPosition()

      assertNear(attachedFit.zoom, deferredFit.zoom, "the first fit used the 1x1 startup viewport")
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
  fun an_animation_completes_and_lands_on_its_target() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
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
  fun a_zero_duration_animation_completes() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.startAtOrigin()

      it.awaitWhileRendering("the instant animation to complete") {
        it.session.animateCameraPosition(TARGET, 0.milliseconds)
      }
    }
  }

  /** A transition another command takes over still resumes its caller, as it does on Android. */
  @Test
  fun a_superseded_animation_resumes_rather_than_hanging() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
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

  /** Cancelling the coroutine must stop the camera and leave the next animation working. */
  @Test
  fun cancelling_an_animation_stops_the_camera_and_leaves_nothing_registered() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
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

      // A leftover registration would make this one resolve early, or never.
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

  /** Closing the map discards its queued events, so an outstanding animation must be released. */
  @Test
  fun closing_the_session_resumes_an_outstanding_animation() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
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

      it.session.close()

      it.pumpUntil("the stranded animation to resume") { animation.isCompleted }
      assertFalse(animation.isCancelled, "teardown should resume the waiter, not cancel it")
      assertEquals(
        endedBeforeClose + 1,
        it.events.count { event -> event == "cameraMoveEnded" },
        "teardown should close the outstanding camera move exactly once: ${it.events}",
      )
    }
  }

  /** Puts the map at a known camera and waits for the owner thread to have applied it. */
  private fun BridgeMapFixture.startAtOrigin() {
    session.setCameraPosition(START)
    // Render first: before the map exists a camera read answers with what was last asked for, which
    // would satisfy the check below without a map to animate.
    pumpUntilRendered()
    pumpUntil("the map to reach its starting camera") {
      abs(session.getCameraPosition().zoom - START.zoom) < 0.001
    }
  }

  private fun BridgeMapFixture.awaitCameraMoving() {
    pumpUntil("the animation to start moving the camera") {
      abs(session.getCameraPosition().zoom - START.zoom) > 0.01
    }
  }

  private companion object {
    val START = CameraPosition(target = Position(0.0, 0.0), zoom = 2.0)
    val TARGET = CameraPosition(target = Position(11.0, 47.0), zoom = 8.0)
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
