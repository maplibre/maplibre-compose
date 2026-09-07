package org.maplibre.compose.camera

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputPanByAwaitingTransition
import org.maplibre.compose.camera.internal.inputRotateAndPitchBy
import org.maplibre.compose.camera.internal.inputRotateAndPitchByAwaitingTransition
import org.maplibre.compose.camera.internal.inputScaleByAwaitingTransition
import org.maplibre.compose.interaction.BearingTargets
import org.maplibre.compose.interaction.CameraBuilder
import org.maplibre.compose.interaction.HapticEmphasis
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.interaction.internal.GestureInputSession
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

class CameraInputIntegrationTest {
  @Test
  fun haptic_feedback_tracks_applied_rotation_but_not_momentum_or_settlement(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          fixture.state.gestureAuthority.updateConfiguration(
            CameraBuilder(CameraConfiguration())
              .apply {
                rotate {
                  snapping()
                  haptics { notch(BearingTargets.at(0.0)) }
                }
              }
              .build()
          )
          fixture.state.setCameraPosition(CameraPosition(zoom = 5.0, bearing = 355.0))
          fixture.settle()
          val ticks = mutableListOf<HapticEmphasis>()
          val input = GestureInputSession(this, fixture.gestures, onHaptic = { ticks += it })
          fixture.gestures.inputRotateAndPitchBy(10.0, 0.0, gestureToken = input.token)
          fixture.pumpUntil("a haptic crossing after applied rotation") { ticks.isNotEmpty() }
          assertEquals(listOf(HapticEmphasis.Standard), ticks)
          fixture.gestures.inputRotateAndPitchBy(
            -10.0,
            0.0,
            gestureToken = input.token,
            feedback = false,
          )
          input.end()
          fixture.awaitWhileRendering("silent momentum and settlement") {
            input.scope.coroutineContext[Job]!!.join()
          }
          assertEquals(listOf(HapticEmphasis.Standard), ticks)
          assertEquals(0.0, fixture.state.cameraPosition.bearing, 1e-6)
        }
      }
    }

  @Test
  fun rotation_settles_after_queued_movement_and_momentum_preserving_other_components():
    MapTestResult = runMapTest {
    coroutineScope {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        fixture.session.setCameraPadding(PaddingValues(start = 65.dp, top = 25.dp, end = 10.dp))
        fixture.state.gestureAuthority.updateConfiguration(
          CameraBuilder(CameraConfiguration())
            .apply {
              rotate { snapping { targets = BearingTargets.evenlySpaced(4, 32.0) } }
            }
            .build()
        )
        for (momentum in listOf(false, true)) {
          val before =
            CameraPosition(target = Position(3.0, 45.0), zoom = 5.0, bearing = 20.0, tilt = 30.0)
          fixture.state.setCameraPosition(before)
          fixture.settle()
          fixture.awaitWhileRendering("rotation settlement") {
            val input = GestureInputSession(this, fixture.gestures)
            fixture.gestures.inputRotateAndPitchBy(8.0, 0.0, gestureToken = input.token)
            if (momentum)
              input.scope.launch {
                fixture.gestures.inputRotateAndPitchByAwaitingTransition(
                  3.0,
                  0.0,
                  0.1.seconds,
                  input.token,
                )
              }
            input.end()
            input.scope.coroutineContext[Job]!!.join()
          }
          val after = fixture.state.cameraPosition
          assertEquals(32.0, after.bearing, 1e-6)
          assertEquals(before.target.longitude, after.target.longitude, 1e-6)
          assertEquals(before.target.latitude, after.target.latitude, 1e-6)
          assertEquals(before.zoom, after.zoom, 1e-6)
          assertEquals(before.tilt, after.tilt, 1e-6)
          assertFalse(fixture.state.isCameraMoving)
        }
      }
    }
  }

  @Test
  fun snapping_does_not_follow_pan_or_override_programmatic_takeover(): MapTestResult = runMapTest {
    coroutineScope {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        fixture.state.gestureAuthority.updateConfiguration(
          CameraBuilder(CameraConfiguration())
            .apply {
              rotate { snapping() }
            }
            .build()
        )
        fixture.state.setCameraPosition(CameraPosition(zoom = 5.0, bearing = 3.0))
        fixture.settle()
        fixture.awaitWhileRendering("pan without rotation") {
          val input = GestureInputSession(this, fixture.gestures)
          fixture.gestures.inputPanBy(10.0, 0.0, input.token)
          input.end()
          input.scope.coroutineContext[Job]!!.join()
        }
        assertEquals(3.0, fixture.state.cameraPosition.bearing, 1e-6)
        val input = GestureInputSession(this, fixture.gestures, animationDuration = 30.seconds)
        fixture.gestures.inputRotateAndPitchBy(1.0, 0.0, gestureToken = input.token)
        input.end()
        fixture.pumpUntil("settling to start") { fixture.state.isCameraMoving }
        fixture.state.setCameraPosition(CameraPosition(zoom = 5.0, bearing = 42.0))
        fixture.awaitWhileRendering("takeover to cancel settlement") {
          input.scope.coroutineContext[Job]!!.join()
        }
        fixture.settle()
        assertEquals(42.0, fixture.state.cameraPosition.bearing, 1e-6)
      }
    }
  }

  @Test
  fun recognized_input_stops_programmatic_motion_before_its_first_delta(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          // Exercise backend interruption even when Android system animations are disabled.
          val animation =
            launch(start = CoroutineStart.UNDISPATCHED) {
              fixture.session.animateCameraPosition(CameraPosition(zoom = 8.0), 30.seconds)
            }
          fixture.pumpUntil("the programmatic animation to start") { fixture.state.isCameraMoving }
          val input = GestureInputSession(this, fixture.gestures)
          try {
            fixture.awaitWhileRendering("recognition to stop the animation") { animation.join() }
            assertTrue(input.token.acceptsCommands)
            assertTrue(fixture.state.cameraPosition.zoom < 8.0)
          } finally {
            input.end()
          }
        }
      }
    }

  @Test
  fun built_in_session_completion_drains_the_backend_before_its_job_finishes(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          fixture.state.setCameraPosition(CameraPosition(zoom = 3.0))
          fixture.settle()
          val input = GestureInputSession(this, fixture.gestures)
          fixture.gestures.scaleBy(2.0, null, gestureToken = input.token)
          input.end()
          fixture.awaitWhileRendering("built-in input completion") {
            input.scope.coroutineContext[Job]!!.join()
          }
          assertEquals(4.0, fixture.state.cameraPosition.zoom, 1e-6)
          assertFalse(fixture.state.isCameraMoving)
        }
      }
    }

  @Test
  fun programmatic_takeover_cancels_the_built_in_ease_and_delivers_its_cancellation():
    MapTestResult = runMapTest {
    coroutineScope {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        val cancelled = CompletableDeferred<Unit>()
        val input = GestureInputSession(this, fixture.gestures) { cancelled.complete(Unit) }
        val ease =
          input.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.gestures.scaleByAwaitingTransition(4.0, null, 30.seconds, input.token)
          }
        fixture.pumpUntil("the built-in ease to start") { fixture.state.isCameraMoving }
        assertFalse(ease.isCompleted)
        fixture.state.setCameraPosition(CameraPosition(zoom = 5.0))
        fixture.awaitWhileRendering("built-in takeover cancellation") { cancelled.await() }
        assertTrue(ease.isCancelled)
        fixture.settle()
        assertEquals(5.0, fixture.state.cameraPosition.zoom, 1e-6)
        assertEquals(CameraMoveReason.PROGRAMMATIC, fixture.state.cameraMoveReason)
      }
    }
  }

  @Test
  fun detaching_delivers_built_in_cancellation_without_further_frames(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          val cancelled = CompletableDeferred<Unit>()
          val input = GestureInputSession(this, fixture.gestures) { cancelled.complete(Unit) }
          fixture.gestures.inputPanBy(10.0, 0.0, gestureToken = input.token)
          fixture.closeSession()
          withTimeout(5.seconds) { cancelled.await() }
          assertFalse(input.token.acceptsCommands)
        }
      }
    }

  @Test
  fun centered_rotation_preserves_the_padded_camera_target(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.awaitMapReady()
      fixture.session.setCameraPadding(PaddingValues(start = 65.dp, top = 25.dp, end = 10.dp))
      fixture.state.setCameraPosition(
        CameraPosition(target = Position(3.0, 45.0), zoom = 5.0, bearing = 20.0, tilt = 30.0)
      )
      fixture.settle()
      val before = fixture.state.cameraPosition
      fixture.awaitWhileRendering("centered rotation") {
        val input = GestureInputSession(this, fixture.gestures)
        fixture.gestures.inputRotateAndPitchByAwaitingTransition(
          20.0,
          5.0,
          Duration.ZERO,
          input.token,
        )
        input.end()
        input.scope.coroutineContext[Job]!!.join()
      }
      val after = fixture.state.cameraPosition
      assertEquals(before.target.longitude, after.target.longitude, 1e-6)
      assertEquals(before.target.latitude, after.target.latitude, 1e-6)
      assertTrue(after.bearing > before.bearing)
      assertTrue(after.tilt > before.tilt)
    }
  }

  @Test
  fun pan_lock_preserves_padded_target_even_when_input_requests_an_anchor(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          fixture.session.setCameraPadding(PaddingValues(start = 65.dp, top = 25.dp, end = 10.dp))
          fixture.state.setCameraPosition(
            CameraPosition(target = Position(3.0, 45.0), zoom = 5.0, bearing = 20.0, tilt = 30.0)
          )
          fixture.settle()
          val before = fixture.state.cameraPosition
          fixture.state.gestureAuthority.updateConfiguration(
            CameraBuilder(CameraConfiguration())
              .apply {
                pan { enabled = false }
              }
              .build()
          )
          fixture.awaitWhileRendering("centered scope zoom") {
            val input = GestureInputSession(this, fixture.gestures)
            fixture.gestures.inputPanBy(20.0, 10.0, input.token)
            fixture.gestures.inputScaleByAwaitingTransition(
              2.0,
              DpOffset(5.dp, 5.dp),
              Duration.ZERO,
              input.token,
            )
            fixture.gestures.inputRotateAndPitchByAwaitingTransition(
              15.0,
              0.0,
              Duration.ZERO,
              input.token,
              DpOffset(5.dp, 5.dp),
            )
            input.end()
            input.scope.coroutineContext[Job]!!.join()
          }
          val after = fixture.state.cameraPosition
          assertEquals(before.target.longitude, after.target.longitude, 1e-6)
          assertEquals(before.target.latitude, after.target.latitude, 1e-6)
          assertEquals(before.zoom + 1.0, after.zoom, 1e-6)
          assertEquals(before.bearing + 15.0, after.bearing, 1e-6)
          assertEquals(before.tilt, after.tilt, 1e-6)
        }
      }
    }

  @Test
  fun zero_duration_awaiting_commands_finish_and_close_the_session(): MapTestResult = runMapTest {
    coroutineScope {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        fixture.state.setCameraPosition(CameraPosition(zoom = 4.0))
        fixture.settle()
        fixture.awaitWhileRendering("zero duration gesture commands") {
          val input = GestureInputSession(this, fixture.gestures)
          fixture.gestures.inputScaleByAwaitingTransition(2.0, null, Duration.ZERO, input.token)
          fixture.gestures.inputRotateAndPitchByAwaitingTransition(
            10.0,
            5.0,
            Duration.ZERO,
            input.token,
          )
          fixture.gestures.inputPanByAwaitingTransition(10.0, 0.0, Duration.ZERO, input.token)
          input.end()
          input.scope.coroutineContext[Job]!!.join()
        }
        assertTrue(
          abs(fixture.state.cameraPosition.target.longitude) > 1e-6,
          "pan did not move the target",
        )
        assertEquals(5.0, fixture.state.cameraPosition.zoom, 1e-6)
        assertEquals(10.0, fixture.state.cameraPosition.bearing, 1e-6)
        assertEquals(5.0, fixture.state.cameraPosition.tilt, 1e-6)
        assertFalse(fixture.state.isCameraMoving)
      }
    }
  }
}
