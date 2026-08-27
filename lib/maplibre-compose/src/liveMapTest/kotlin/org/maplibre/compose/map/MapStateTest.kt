package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.OpRecordingStyleBinding
import org.maplibre.compose.style.RecordingStyleBinding

private fun testSource(id: String) =
  RasterSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

@OptIn(ExperimentalCoroutinesApi::class)
class MapStateTest {

  private fun TestScope.mapState(
    cameraPosition: CameraPosition = CameraPosition(),
    hostDispatcher: RecordingHostDispatcher =
      RecordingHostDispatcher(StandardTestDispatcher(testScheduler)),
  ) =
    MapState(
      cameraPosition = cameraPosition,
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      logger = null,
      hostDispatcher = hostDispatcher,
    )

  @Test
  fun survives_a_detach_attach_cycle_and_rewires_a_new_session() = runTest {
    val state = mapState()
    val source = testSource("tiles")

    state.setStyleContent { RasterLayer(id = "raster", source = source) }

    val first = FakeMapAdapter()
    val firstBinding = OpRecordingStyleBinding()
    state.attachSession(first)
    state.callbacks.onStyleChanged(first, firstBinding)
    testScheduler.advanceUntilIdle()

    assertSame(first, state.attachedAdapter)
    assertTrue(
      first.calls.any { it == "setCameraPosition" },
      "the attaching session starts the map at the recorded camera",
    )

    state.detachSession()
    // The engine session unloads its style when it goes away; the fake models that here.
    firstBinding.unload()
    testScheduler.advanceUntilIdle()
    val firstCallsAfterDetach = first.calls.toList()
    val firstOpsAfterDetach = firstBinding.ops.toList()

    assertFalse(state.isAttached)
    assertFalse(state.styleNode.binding.isLoaded)
    assertTrue(state.sources.ids.isEmpty())

    val second = FakeMapAdapter()
    val secondBinding = OpRecordingStyleBinding()
    state.attachSession(second)
    state.callbacks.onStyleChanged(second, secondBinding)
    testScheduler.advanceUntilIdle()

    // The new session gets the deferred camera and a fresh apply of the same desired state.
    assertSame(second, state.attachedAdapter)
    assertTrue(
      second.calls.any { it == "setCameraPosition" },
      "the new session starts the map at the recorded camera",
    )
    assertEquals(listOf("addSource:tiles", "addLayer:raster"), secondBinding.ops.toList())

    // The detached session and its dead style saw nothing after the detach.
    assertEquals(firstCallsAfterDetach, first.calls.toList())
    assertEquals(firstOpsAfterDetach, firstBinding.ops.toList())

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun close_after_detach_shuts_down_the_recomposer_and_the_dispatcher() = runTest {
    val hostDispatcher = RecordingHostDispatcher(StandardTestDispatcher(testScheduler))
    val state = mapState(hostDispatcher = hostDispatcher)
    state.setStyleContent { RasterLayer(id = "raster", source = testSource("tiles")) }

    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, RecordingStyleBinding())
    testScheduler.advanceUntilIdle()

    state.detachSession()
    state.close()
    testScheduler.advanceUntilIdle()

    // The host releases its dispatcher last, after it has shut the recomposer down.
    assertTrue(hostDispatcher.closed)
  }

  @Test
  fun a_closed_state_refuses_a_new_session_and_close_is_idempotent() = runTest {
    val state = mapState()
    state.close()
    state.close()
    testScheduler.advanceUntilIdle()

    val error = assertFailsWith<IllegalStateException> { state.attachSession(FakeMapAdapter()) }
    assertTrue(
      "closed" in error.message.orEmpty(),
      "the error names the closed-state contract: ${error.message}",
    )
  }

  @Test
  fun a_second_concurrent_session_attach_throws() = runTest {
    val state = mapState()
    val first = FakeMapAdapter()
    state.attachSession(first)

    val error = assertFailsWith<IllegalStateException> { state.attachSession(FakeMapAdapter()) }
    assertTrue(
      "one MapState shows one MaplibreMap" in error.message.orEmpty(),
      "the error names the single-session contract: ${error.message}",
    )

    // The same session may re-attach, which the composable does when its options change.
    state.attachSession(first)
    assertSame(first, state.attachedAdapter)

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_pre_attach_camera_call_fails_on_close_instead_of_hanging() = runTest {
    val state = mapState()
    var failure: Throwable? = null
    val waiter = launch {
      try {
        state.animateCamera(CameraPosition(zoom = 3.0))
      } catch (error: IllegalStateException) {
        failure = error
      }
    }
    testScheduler.advanceUntilIdle()
    assertFalse(waiter.isCompleted, "the call waits while the state is open")

    state.close()
    Snapshot.sendApplyNotifications()
    testScheduler.advanceUntilIdle()

    assertTrue(waiter.isCompleted, "the call fails promptly once the state closes")
    assertIs<IllegalStateException>(failure)
  }

  @Test
  fun composition_layer_changes_refresh_the_layer_ids() = runTest {
    val state = mapState()
    var show by mutableStateOf(true)
    state.setStyleContent { if (show) BackgroundLayer(id = "bg-toggled") }

    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, RecordingStyleBinding())
    testScheduler.advanceUntilIdle()
    assertTrue("bg-toggled" in state.layers.ids, "the composed layer reaches layers.ids")

    show = false
    Snapshot.sendApplyNotifications()
    testScheduler.advanceUntilIdle()
    assertFalse("bg-toggled" in state.layers.ids, "the removed layer leaves layers.ids")

    show = true
    Snapshot.sendApplyNotifications()
    testScheduler.advanceUntilIdle()
    assertTrue("bg-toggled" in state.layers.ids, "the re-added layer returns to layers.ids")

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun detach_resets_the_session_hooks_so_a_later_load_event_fires_nothing() = runTest {
    val state = mapState()
    state.setStyleContent {}
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, OpRecordingStyleBinding())
    testScheduler.advanceUntilIdle()

    var finished = 0
    var failed = 0
    state.callbacks.onMapLoadFinished = { finished++ }
    state.callbacks.onMapLoadFailed = { failed++ }
    state.detachSession()

    // A retained core can deliver load events after the composable is gone.
    state.callbacks.onMapFinishedLoading(adapter)
    state.callbacks.onMapFailLoading("late failure")
    assertEquals(0, finished, "a disposed composable's load hook fired")
    assertEquals(0, failed, "a disposed composable's failure hook fired")

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun clearing_the_style_content_removes_it_from_the_style() = runTest {
    val state = mapState()
    state.setStyleContent { BackgroundLayer(id = "bg-cleared") }

    val adapter = FakeMapAdapter()
    val binding = RecordingStyleBinding()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)
    testScheduler.advanceUntilIdle()
    assertTrue(binding.layerExists("bg-cleared"), "the content applies to the style")

    state.clearStyleContent()
    Snapshot.sendApplyNotifications()
    testScheduler.advanceUntilIdle()
    assertFalse(binding.layerExists("bg-cleared"), "clearing removes the content from the style")

    state.close()
    testScheduler.advanceUntilIdle()
  }
}
