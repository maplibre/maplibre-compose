package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

class CameraInputIntegrationTest {
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
  fun normal_completion_observes_accepted_native_or_js_commands_before_returning(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          fixture.state.setCameraPosition(CameraPosition(zoom = 4.0))
          fixture.settle()
          val before = fixture.state.cameraPosition
          lateinit var retained: CameraInputScope
          fixture.awaitWhileRendering("gesture command fence") {
            fixture.state.withCameraInput {
              retained = this
              panBy(10.0, 0.0)
              panBy(10.0, 0.0)
            }
          }
          assertTrue(
            abs(fixture.state.cameraPosition.target.longitude - before.target.longitude) > 0.1
          )
          assertFalse(fixture.state.isCameraMoving)
          assertFailsWith<IllegalStateException> { retained.panBy(10.0, 0.0) }
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
        fixture.state.withCameraInput {
          rotateAndPitchByAwaitingTransition(20.0, 5.0, Duration.ZERO)
        }
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
            fixture.state.withCameraInput {
              panBy(20.0, 10.0)
              scaleByAwaitingTransition(2.0, DpOffset(5.dp, 5.dp), Duration.ZERO)
              rotateAndPitchByAwaitingTransition(15.0, 0.0, Duration.ZERO, DpOffset(5.dp, 5.dp))
            }
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
          fixture.state.withCameraInput {
            scaleByAwaitingTransition(2.0, duration = Duration.ZERO)
            rotateAndPitchByAwaitingTransition(10.0, 5.0, Duration.ZERO)
            panByAwaitingTransition(10.0, 0.0, Duration.ZERO)
          }
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

  @Test
  fun programmatic_takeover_returns_normally_to_the_application_input_loop(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          var returned = false
          val started = CompletableDeferred<Unit>()
          val input =
            launch(start = CoroutineStart.UNDISPATCHED) {
              fixture.state.withCameraInput {
                panBy(10.0, 0.0)
                started.complete(Unit)
                awaitCancellation()
              }
              returned = true
            }
          started.await()
          fixture.pumpUntil("camera input to start moving") { fixture.state.isCameraMoving }
          fixture.state.setCameraPosition(CameraPosition(zoom = 5.0))
          fixture.awaitWhileRendering("programmatic takeover fence") { input.join() }
          assertTrue(returned)
          assertFalse(input.isCancelled)
          fixture.settle()
          assertEquals(5.0, fixture.state.cameraPosition.zoom, 1e-6)
          assertEquals(CameraMoveReason.PROGRAMMATIC, fixture.state.cameraMoveReason)
          assertFalse(fixture.state.isCameraMoving)
        }
      }
    }

  @Test
  fun closing_a_map_releases_an_active_scope_without_more_frames(): MapTestResult = runMapTest {
    coroutineScope {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        val started = CompletableDeferred<Unit>()
        var returned = false
        val input = launch {
          fixture.state.withCameraInput {
            panBy(10.0, 0.0)
            started.complete(Unit)
            awaitCancellation()
          }
          returned = true
        }
        started.await()
        fixture.closeSession()
        withTimeout(5.seconds) { input.join() }
        assertTrue(returned)
        assertFalse(input.isCancelled)
      }
    }
  }
}
