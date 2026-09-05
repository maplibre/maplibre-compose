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
import kotlinx.coroutines.CoroutineScope
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
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

class GestureCameraIntegrationTest {
  @Test
  fun pending_tap_delivery_survives_programmatic_takeover_without_restoring_its_zoom():
    MapTestResult = runMapTest {
    coroutineScope {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        val queryStarted = CompletableDeferred<Unit>()
        val queryResult = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val work = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + work)
        var delivered = false
        try {
          val clicks =
            object : MapInteractionTarget {
              override fun capture(family: TapFamily) =
                MapClickPath({ true }) {
                  queryStarted.complete(Unit)
                  queryResult.await()
                  delivered = true
                  ClickResult.Pass
                }
            }
          val continuation = GestureContinuation(scope)
          val dispatcher = MapTapDispatcher(scope, clicks) { MapGestures.Standard }
          val generation = fixture.gestures.observeInput()
          dispatcher.dispatch(
            TapFamily.DoubleTap,
            GesturePointerSample(1, 10, DpOffset.Zero, null, emptySet(), emptySet(), emptySet()),
          ) {
            continuation.launchDiscreteTransition(
              fixture.gestures,
              {},
              { token ->
                scaleByAwaitingTransition(2.0, null, Duration.ZERO, token)
              },
              generation,
            )
            finished.complete(Unit)
          }
          fixture.awaitWhileRendering("tap query starts") { queryStarted.await() }
          fixture.state.setCameraPosition(CameraPosition(zoom = 5.0))
          queryResult.complete(Unit)
          fixture.awaitWhileRendering("tap application delivery") { finished.await() }
          fixture.settle()
          assertTrue(delivered)
          assertEquals(5.0, fixture.state.cameraPosition.zoom, 1e-6)
          assertEquals(CameraMoveReason.PROGRAMMATIC, fixture.state.cameraMoveReason)
        } finally {
          work.cancel()
          work.join()
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
            fixture.gestures.scaleByAwaitingTransition(4.0, null, 1.seconds, input.token)
          }
        fixture.pump(2)
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
          fixture.gestures.moveBy(10.0, 0.0, gestureToken = input.token)
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
          lateinit var retained: GestureCameraScope
          fixture.awaitWhileRendering("gesture command fence") {
            fixture.state.gestureCamera.withGesture {
              retained = this
              moveBy(10.0, 0.0)
              moveBy(10.0, 0.0)
            }
          }
          assertTrue(
            abs(fixture.state.cameraPosition.target.longitude - before.target.longitude) > 0.1
          )
          assertFalse(fixture.state.isCameraMoving)
          assertFailsWith<IllegalStateException> { retained.moveBy(10.0, 0.0) }
        }
      }
    }

  @Test
  fun camera_center_scale_preserves_the_target_with_asymmetric_padding(): MapTestResult =
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
          fixture.awaitWhileRendering("centered scope zoom") {
            fixture.state.gestureCamera.withGesture { scaleBy(2.0) }
          }
          val after = fixture.state.cameraPosition
          assertEquals(before.target.longitude, after.target.longitude, 1e-6)
          assertEquals(before.target.latitude, after.target.latitude, 1e-6)
          assertEquals(before.zoom + 1.0, after.zoom, 1e-6)
          assertEquals(before.bearing, after.bearing, 1e-6)
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
          fixture.state.gestureCamera.withGesture {
            scaleByAwaitingTransition(2.0, duration = Duration.ZERO)
            rotateAndPitchByAwaitingTransition(10.0, 5.0, Duration.ZERO)
            moveByAwaitingTransition(10.0, 0.0, Duration.ZERO)
          }
        }
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
              fixture.state.gestureCamera.withGesture {
                moveBy(10.0, 0.0)
                started.complete(Unit)
                awaitCancellation()
              }
              returned = true
            }
          started.await()
          fixture.pump(2)
          assertTrue(fixture.state.isCameraMoving)
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
          fixture.state.gestureCamera.withGesture {
            moveBy(10.0, 0.0)
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
