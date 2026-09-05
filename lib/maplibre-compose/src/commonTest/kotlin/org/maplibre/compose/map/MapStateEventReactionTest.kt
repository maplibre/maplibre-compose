package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * [MapState] derives the camera viewport, flag, and reason from [MapEvent] and the gesture fact,
 * then publishes the event it accepted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapStateEventReactionTest {

  @Test
  fun an_engine_camera_change_moves_the_camera_and_reads_as_programmatic() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = presentedAdapter(state)

    state.onEvent(adapter, MapEvent.CameraMoveStarted(animated = false))

    assertTrue(state.isCameraMoving)
    assertEquals(CameraMoveReason.PROGRAMMATIC, state.cameraMoveReason)
    assertEquals(adapter.currentViewport, state.viewport)

    state.onEvent(adapter, MapEvent.CameraMoveEnded(animated = false))

    assertFalse(state.isCameraMoving)
    assertEquals(CameraMoveReason.PROGRAMMATIC, state.cameraMoveReason)

    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_gesture_spans_the_camera_changes_the_engine_reports_one_by_one() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = presentedAdapter(state)

    state.setGestureActive(adapter, true)

    assertTrue(state.isCameraMoving)
    assertEquals(CameraMoveReason.GESTURE, state.cameraMoveReason)

    repeat(3) {
      state.onEvent(adapter, MapEvent.CameraMoveStarted(animated = false))
      state.onEvent(adapter, MapEvent.CameraMoveEnded(animated = false))
      assertTrue(state.isCameraMoving, "the drag ended at jump $it")
      assertEquals(CameraMoveReason.GESTURE, state.cameraMoveReason)
    }

    state.setGestureActive(adapter, false)

    assertFalse(state.isCameraMoving)
    assertEquals(CameraMoveReason.GESTURE, state.cameraMoveReason)

    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_camera_change_reports_a_move_while_the_map_has_no_viewport() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = presentedAdapter(state, viewport = null)

    state.onEvent(adapter, MapEvent.CameraMoveStarted(animated = false))

    assertTrue(state.isCameraMoving)
    assertEquals(CameraMoveReason.PROGRAMMATIC, state.cameraMoveReason)

    state.onEvent(adapter, MapEvent.CameraMoveEnded(animated = false))

    assertFalse(state.isCameraMoving)

    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun an_adapter_that_is_not_the_current_presentation_reports_nothing() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    presentedAdapter(state)
    val other = PresentationTestAdapter()

    state.onEvent(other, MapEvent.CameraMoveStarted(animated = false))
    state.setGestureActive(other, true)

    assertFalse(state.isCameraMoving)
    assertEquals(CameraMoveReason.NONE, state.cameraMoveReason)

    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun engagement_follows_the_presented_map_and_ends_on_detach() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = presentedAdapter(state)
    val other = PresentationTestAdapter()

    state.setEngaged(other, engaged = true)
    assertFalse(state.isEngaged)

    state.setEngaged(adapter, engaged = true)
    assertTrue(state.isEngaged)

    state.invalidatePresentation(adapter)
    assertFalse(state.isEngaged)

    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_detached_map_stops_reporting_a_move() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = presentedAdapter(state)
    state.setGestureActive(adapter, true)
    state.onEvent(adapter, MapEvent.CameraMoveStarted(animated = false))
    assertTrue(state.isCameraMoving)

    state.invalidatePresentation(adapter)

    assertFalse(state.isCameraMoving)

    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun an_accepted_event_reaches_the_public_flow_after_its_reaction() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = presentedAdapter(state)
    val other = PresentationTestAdapter()
    val published = mutableListOf<Pair<MapEvent, Boolean>>()
    // An unconfined collector runs at the emission, so it reads the state that the event produced
    // rather than the state after every call returned.
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      state.events.collect { published += it to state.isCameraMoving }
    }

    state.onEvent(adapter, MapEvent.StyleLoaded)
    state.onEvent(other, MapEvent.StyleLoaded)
    state.onEvent(adapter, MapEvent.CameraMoveStarted(animated = false))

    assertEquals(
      listOf(
        MapEvent.StyleLoaded to false,
        MapEvent.CameraMoveStarted(animated = false) to true,
      ),
      published,
    )

    state.close()
    state.awaitClosed()
    runtime.close()
  }

  private fun presentedAdapter(
    state: MapState,
    viewport: Viewport? = testViewport(),
  ): PresentationTestAdapter {
    val adapter = PresentationTestAdapter()
    adapter.currentViewport = viewport
    val token = state.reservePresentation(MapPresentationOwnerToken())
    state.publishPresentation(token, adapter)
    return adapter
  }

  private fun testViewport(): Viewport =
    Viewport(
      size = DpSize(100.dp, 100.dp),
      visibleBoundingBox = BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0)),
      visibleRegion =
        VisibleRegion(
          farLeft = Position(-1.0, 1.0),
          farRight = Position(1.0, 1.0),
          nearLeft = Position(-1.0, -1.0),
          nearRight = Position(1.0, -1.0),
        ),
      metersPerDpAtTarget = 1.0,
    )
}
