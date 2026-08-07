package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
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
import org.maplibre.compose.mlnffi.BridgeMapFixture.Companion.RETINA_EXTENT
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

      it.session.close()

      it.pumpUntil("the stranded animation to resume") { animation.isCompleted }
      assertFalse(animation.isCancelled, "teardown should resume the waiter, not cancel it")
    }
  }

  /**
   * A density change rebuilds the map inside one long-lived adapter, which Compose cannot see, so
   * the session has to carry the camera across itself — where the map is now, not where it was last
   * told to go.
   */
  @Test
  fun a_density_change_preserves_the_camera() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.startAtOrigin()
      // Panned after the last setCameraPosition, so a session replaying the requested camera rather
      // than the live one fails here.
      it.session.moveBy(deltaX = 64.0, deltaY = 0.0)
      it.pumpUntil("the pan to apply") {
        it.session.getCameraPosition().target.longitude != START.target.longitude
      }
      val panned = it.session.getCameraPosition()

      it.hasRendered = false
      it.pumpUntilRendered(extent = RETINA_EXTENT)

      val afterRebuild = it.session.getCameraPosition()
      assertNear(panned.zoom, afterRebuild.zoom, "zoom should survive the rebuild")
      assertNear(
        panned.target.longitude,
        afterRebuild.target.longitude,
        "longitude should survive the rebuild",
      )
      assertNear(
        panned.target.latitude,
        afterRebuild.target.latitude,
        "latitude should survive the rebuild",
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

    fun assertNear(expected: Double, actual: Double, message: String) {
      assertTrue(abs(expected - actual) < 0.01, "$message (expected $expected, was $actual)")
    }
  }
}
