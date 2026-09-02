package org.maplibre.compose.map

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import js.objects.unsafeJso
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.gljs.MapEvent
import org.maplibre.compose.gljs.isNear
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class BrowserCameraTransitionLifecycleTest {

  @Test
  fun cancelling_transitions_releases_an_animation_queued_before_the_first_style(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.session.setBaseStyle(BaseStyle.Empty)
        val animation =
          CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            it.session.animateCameraPosition(STALE_CAMERA, 60.seconds)
          }

        assertFalse(animation.isCompleted, "the animation should be queued before cancellation")
        it.gestures.cancelTransitions()
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
  fun a_failed_initial_style_resumes_a_queued_animation(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Json("{ this is not json"))
      val animation =
        CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
          it.session.animateCameraPosition(STALE_CAMERA, 60.seconds)
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
      val state = runtime.createMapState(initialCameraPosition = CURRENT_CAMERA, baseStyle = STYLE)
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
      departedEngine.fire("move", unsafeJso<MapEvent>())
      waitForIdle()

      assertTrue(state.cameraPosition.isNear(CURRENT_CAMERA))

      runtime.close()
      runtime.awaitClosed()
    }

  private companion object {
    val STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val CURRENT_CAMERA = CameraPosition(target = Position(11.0, 47.0), zoom = 8.0)
    val STALE_CAMERA = CameraPosition(target = Position(-122.4, 37.8), zoom = 12.0)
  }
}
