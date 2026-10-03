package org.maplibre.compose.map

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import js.objects.unsafeJso
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.gljs.GlJsMapEvent
import org.maplibre.compose.gljs.isNear
import org.maplibre.compose.gljs.isPointOnMapSurface
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.toPoint
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class BrowserCameraTransitionLifecycleTest {

  @Test
  fun a_partial_browser_update_stops_the_previous_animation_and_keeps_omitted_values():
    MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.awaitMapReady()
      fixture.state.setCameraPosition(CameraPosition(zoom = 3.0))
      fixture.pump(frames = 2)
      val zoom = launch {
        fixture.state.animateCamera(CameraUpdate(zoom = 8.0), CameraAnimation.Ease(4.seconds))
      }
      fixture.pumpUntil("zoom to start") { fixture.state.cameraPosition.zoom > 3.1 }
      val bearing = launch {
        fixture.state.animateCamera(CameraUpdate(bearing = 90.0), CameraAnimation.Ease(1.seconds))
      }
      fixture.pumpUntil("the browser to supersede zoom") { zoom.isCompleted }
      assertFalse(zoom.isCancelled)
      assertFalse(bearing.isCompleted)
      val stoppedZoom = fixture.state.cameraPosition.zoom
      assertTrue(stoppedZoom < 8.0)
      fixture.pumpUntil("bearing to finish") { bearing.isCompleted }
      assertFalse(bearing.isCancelled)
      assertEquals(stoppedZoom, fixture.state.cameraPosition.zoom, 0.001)
      assertEquals(90.0, fixture.state.cameraPosition.bearing, 0.001)
      assertFalse(fixture.state.isCameraMoving)
    }
  }

  @Test
  fun a_screen_anchor_above_the_horizon_is_rejected_in_a_distant_world_copy(): MapTestResult =
    runMapTest {
      createMapFixture(MapExtent.fromLogical(width = 2048, height = 512, scaleFactor = 1.0)).use {
        fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        fixture.session.setCameraConstraints(CameraConstraints(maxPitch = 85.0))
        fixture.state.setCameraPosition(CameraPosition(zoom = 1.0, tilt = 80.0))
        fixture.pumpUntil("the pitched camera to apply") {
          abs(fixture.session.getCameraPosition().tilt - 80.0) < 0.01
        }
        val point = DpOffset(50.dp, 0.dp)
        val map = requireNotNull((fixture.session as GlJsMapSession).engineMapForTest())
        assertFalse(
          map.isPointOnMapSurface(point.toPoint()),
          "the anchor must lie above the horizon",
        )
        val location = requireNotNull(fixture.state.positionFromScreenLocation(point))
        val before = fixture.session.getCameraPosition()
        assertTrue(
          abs(location.longitude - before.target.longitude) > 180.0,
          "the unprojected location must lie in a distant world copy: $location",
        )
        assertFailsWith<IllegalArgumentException> {
          fixture.awaitWhileRendering("anchor validation") {
            fixture.state.animateCameraAround(CameraAnchor.Screen(point), zoom = 3.0)
          }
        }
        assertTrue(fixture.session.getCameraPosition().isNear(before))
      }
    }

  @Test
  fun cancelling_transitions_releases_an_animation_queued_before_the_first_style(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.session.setBaseStyle(BaseStyle.Empty)
        val animation =
          launch(start = CoroutineStart.UNDISPATCHED) {
            it.session.animateCamera(STALE_CAMERA.toCameraUpdate(), CameraAnimation.Fly(60.seconds))
          }

        assertFalse(animation.isCompleted, "the animation should be queued before cancellation")
        it.gestures.interruptCamera()
        it.pumpUntil("transition cancellation to release the queued animation") {
          animation.isCompleted
        }
        it.loadStyle(BaseStyle.Empty)

        assertFalse(animation.isCancelled, "transition cancellation should resume the waiter")
        assertTrue(
          kotlin.math.abs(it.session.getCameraPosition().zoom) < 0.01,
          "the cancelled animation should not start after the style loads",
        )
      }
    }

  @Test
  fun public_camera_takeover_rejects_an_animation_queued_before_initial_style(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.session.setBaseStyle(BaseStyle.Empty)
          val animation =
            launch(start = CoroutineStart.UNDISPATCHED) {
              fixture.state.animateCamera(
                STALE_CAMERA.toCameraUpdate(),
                CameraAnimation.Fly(60.seconds),
              )
            }
          assertFalse(animation.isCompleted)
          fixture.state.setCameraPosition(CURRENT_CAMERA)
          withTimeout(5.seconds) { animation.join() }
          fixture.loadStyle(BaseStyle.Empty)
          fixture.settle()
          assertTrue(animation.isCancelled)
          assertTrue(fixture.state.cameraPosition.isNear(CURRENT_CAMERA))
        }
      }
    }

  @Test
  fun a_failed_initial_style_resumes_a_queued_animation(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Json("{ this is not json"))
      val animation =
        launch(start = CoroutineStart.UNDISPATCHED) {
          it.session.animateCamera(STALE_CAMERA.toCameraUpdate(), CameraAnimation.Fly(60.seconds))
        }

      assertFalse(animation.isCompleted, "the animation should wait for the initial style result")
      it.pumpUntil("the failed style to release the queued animation") {
        it.errors.isNotEmpty() && animation.isCompleted
      }

      assertFalse(animation.isCancelled, "a style failure should resume the waiter normally")
    }
  }

  @Test
  fun a_destroyed_web_map_cannot_move_the_logical_map_or_a_cached_presentation(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val state = runtime.createMapState(cameraPosition = CURRENT_CAMERA, baseStyle = STYLE)
      val presented = mutableStateOf(true)

      setBrowserMapContent { if (presented.value) MaplibreMap(state = state) }
      waitUntilMap("the Web presentation to become ready") {
        state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
      }
      val departedPresentation = requireNotNull(state.currentMapAttachment)
      val departedSession = departedPresentation.adapter as GlJsMapSession
      val departedEngine = requireNotNull(departedSession.engineMapForTest())

      runOnIdle { presented.value = false }
      waitUntilMap("the GL JS map to be destroyed") {
        state.currentMapAttachment == null && departedSession.engineMapForTest() == null
      }

      departedSession.setCameraPosition(STALE_CAMERA)
      departedEngine.fire("move", unsafeJso<GlJsMapEvent>())
      waitForIdle()

      assertTrue(state.cameraPosition.isNear(CURRENT_CAMERA))

      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun an_immediate_anchor_is_retired_before_a_later_viewport_change(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.awaitMapReady()
      fixture.awaitWhileRendering("the immediate anchored movement") {
        fixture.state.animateCameraAround(
          CameraAnchor.Geographic(Position(0.0, 0.0)),
          zoom = 4.0,
          animation = CameraAnimation.Ease(0.milliseconds),
        )
      }
      val bearing = launch {
        fixture.state.animateCamera(CameraUpdate(bearing = 90.0), CameraAnimation.Ease(1.seconds))
      }
      fixture.pumpUntil("bearing to start") { fixture.state.cameraPosition.bearing > 1.0 }
      fixture.resize(MapExtent.fromLogical(width = 600, height = 400, scaleFactor = 1.0))
      fixture.pumpUntil("bearing to finish after the resize") { bearing.isCompleted }
      assertFalse(bearing.isCancelled)
      assertEquals(90.0, fixture.state.cameraPosition.bearing, 0.001)
    }
  }

  @Test
  fun a_moveend_callback_can_start_a_movement_that_waits_for_its_own_end(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.awaitMapReady()
        val session = fixture.session as GlJsMapSession
        val callbacks = session.callbacks
        var replacement: Job? = null
        session.callbacks =
          object : MapAdapter.Callbacks by callbacks {
            override fun onEvent(map: MapAdapter, event: MapEvent) {
              callbacks.onEvent(map, event)
              if (event is MapEvent.CameraMoveEnded && replacement == null) {
                replacement =
                  launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.state.animateCamera(
                      CameraUpdate(bearing = 90.0),
                      CameraAnimation.Ease(1.seconds),
                    )
                  }
              }
            }
          }
        val first = launch {
          fixture.state.animateCamera(
            CameraUpdate(zoom = 4.0),
            CameraAnimation.Ease(100.milliseconds),
          )
        }
        fixture.pumpUntil("the callback movement to start") {
          first.isCompleted && replacement != null
        }
        assertFalse(requireNotNull(replacement).isCompleted)
        fixture.pumpUntil("the callback movement to finish") {
          requireNotNull(replacement).isCompleted
        }
        assertFalse(requireNotNull(replacement).isCancelled)
        assertEquals(90.0, fixture.state.cameraPosition.bearing, 0.001)
        session.callbacks = callbacks
      }
    }

  private companion object {
    val STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val CURRENT_CAMERA = CameraPosition(target = Position(11.0, 47.0), zoom = 8.0)
    val STALE_CAMERA = CameraPosition(target = Position(-122.4, 37.8), zoom = 12.0)
  }
}
