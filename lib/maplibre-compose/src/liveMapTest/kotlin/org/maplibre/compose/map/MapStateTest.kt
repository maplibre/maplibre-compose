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
import kotlinx.coroutines.CoroutineDispatcher
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
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

private fun testSource(id: String) =
  RasterSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

@OptIn(ExperimentalCoroutinesApi::class)
class MapStateTest {

  private fun TestScope.mapState(
    cameraPosition: CameraPosition = CameraPosition(),
    hostDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
  ) =
    MapState(
      cameraPosition = cameraPosition,
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      logger = null,
      hostDispatcher = hostDispatcher,
    )

  /**
   * One state walks a session generation: a first session attaches (and refuses a concurrent
   * second), detaches with its hooks reset and its style dead, and a new session rewires the
   * recorded camera and desired style.
   */
  @Test
  fun a_session_attaches_refuses_a_rival_detaches_dead_and_a_new_session_rewires() = runTest {
    val state = mapState()
    val source = testSource("tiles")

    state.setStyleComposition { RasterLayer(id = "raster", source = source) }

    val first = FakeMapAdapter()
    val firstBinding = OpRecordingStyleBinding()
    state.attachSession(first)
    state.callbacks.onStyleChanged(first, firstBinding)
    state.host.awaitPendingWork()

    assertSame(first, state.attachedAdapter)
    assertTrue(
      first.calls.any { it == "setCameraPosition" },
      "the attaching session starts the map at the recorded camera",
    )

    // A second concurrent session is refused while the first is attached.
    val error = assertFailsWith<IllegalStateException> { state.attachSession(FakeMapAdapter()) }
    assertTrue(
      "one MapState shows one MaplibreMap" in error.message.orEmpty(),
      "the error names the single-session contract: ${error.message}",
    )
    // The same session may re-attach, which the composable does when its options change.
    state.attachSession(first)
    assertSame(first, state.attachedAdapter)

    // The composable's load hooks are set when the session departs.
    var finished = 0
    var failed = 0
    state.callbacks.onMapLoadFinished = { finished++ }
    state.callbacks.onMapLoadFailed = { failed++ }

    state.detachSession()
    // The engine session unloads its style when it goes away; the fake models that here.
    firstBinding.unload()
    state.host.awaitPendingWork()
    val firstCallsAfterDetach = first.calls.toList()
    val firstOpsAfterDetach = firstBinding.ops.toList()

    assertFalse(state.isAttached)
    assertFalse(state.styleNode.binding.isLoaded)
    assertTrue(state.sources.ids.isEmpty())

    // A retained core can deliver load events after the composable is gone.
    state.callbacks.onMapFinishedLoading(first)
    state.callbacks.onMapFailLoading("late failure")
    assertEquals(0, finished, "a disposed composable's load hook fired")
    assertEquals(0, failed, "a disposed composable's failure hook fired")

    val second = FakeMapAdapter()
    val secondBinding = OpRecordingStyleBinding()
    state.callbacks.onMapLoadFinished = { finished++ }
    state.attachSession(second)
    // Load progress is loadState. Attach does not replay hooks for a load that already finished.
    assertIs<MapLoadState.Ready>(state.loadState)
    assertEquals(0, finished, "attach must not replay a load hook that already ran")
    state.callbacks.onStyleChanged(second, secondBinding)
    state.host.awaitPendingWork()

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

  /**
   * Composed style composition changes against one live session: a toggle removes and re-adds its
   * layer, and clearing the content removes it from the style.
   */
  @Test
  fun style_content_toggles_and_clears_against_a_live_session() = runTest {
    val state = mapState()
    var show by mutableStateOf(true)
    state.setStyleComposition { if (show) BackgroundLayer(id = "bg-user") }

    val adapter = FakeMapAdapter()
    val binding = RecordingStyleBinding()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)
    state.host.awaitPendingWork()
    assertTrue("bg-user" in state.layers.ids, "the composed layer reaches layers.ids")
    assertTrue(binding.layerExists("bg-user"), "the content applies to the style")

    // Each toggle waits on the host's quiescence rather than the scheduler's, because a platform
    // GlobalSnapshotManager started by an earlier test can deliver the write's apply notification
    // from its own thread, after advanceUntilIdle has already drained the queue.
    show = false
    state.host.awaitPendingWork()
    assertFalse("bg-user" in state.layers.ids, "the removed layer leaves layers.ids")

    show = true
    state.host.awaitPendingWork()
    assertTrue("bg-user" in state.layers.ids, "the re-added layer returns to layers.ids")

    state.clearStyleComposition()
    state.host.awaitPendingWork()
    assertFalse(binding.layerExists("bg-user"), "clearing removes the content from the style")

    state.close()
    testScheduler.advanceUntilIdle()
  }

  /**
   * The close path after a session came and went: the host shuts the recomposer down, a second
   * close is a no-op, and the closed state refuses a new session.
   */
  @Test
  fun close_after_detach_tears_down_the_host_idempotently_and_refuses_new_sessions() = runTest {
    val state = mapState()
    state.setStyleComposition { RasterLayer(id = "raster", source = testSource("tiles")) }

    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, RecordingStyleBinding())
    state.host.awaitPendingWork()

    state.detachSession()
    state.close()
    state.close()
    testScheduler.advanceUntilIdle()

    // The teardown has finished once the recomposer reports shut down.
    state.host.awaitShutdown()

    val error = assertFailsWith<IllegalStateException> { state.attachSession(FakeMapAdapter()) }
    assertTrue(
      "closed" in error.message.orEmpty(),
      "the error names the closed-state contract: ${error.message}",
    )
  }

  // A camera call suspended before any session ever attached is an interleaving the walks above
  // cannot reach, because they attach before they close.
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
  fun fitCamera_publishes_the_resulting_camera_before_returning() = runTest {
    val state = mapState()
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    val box = BoundingBox(southwest = Position(0.0, 0.0), northeast = Position(1.0, 2.0))
    state.fitCamera(box, bearing = 15.0, tilt = 10.0)
    assertEquals(15.0, state.camera.bearing)
    assertEquals(10.0, state.camera.tilt)
    assertEquals(box.northeast, state.camera.target)
    assertTrue("fitCameraPosition" in adapter.calls)
  }

  @Test
  fun cancelled_fit_before_attach_does_not_mutate_the_adapter() = runTest {
    val state = mapState()
    val box = BoundingBox(southwest = Position(0.0, 0.0), northeast = Position(1.0, 1.0))
    val job = launch { state.fitCamera(box) }
    testScheduler.advanceUntilIdle()
    job.cancel()
    testScheduler.advanceUntilIdle()
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    testScheduler.advanceUntilIdle()
    assertTrue(
      adapter.calls.none { it == "fitCameraPosition" },
      "a cancelled queued fit must not run after a later attach: ${adapter.calls}",
    )
    state.close()
  }

  @Test
  fun claimSessionConfig_rejects_a_rival_owner() = runTest {
    val state = mapState()
    val winner = Any()
    val rival = Any()
    assertTrue(state.claimSessionConfig(winner))
    assertFalse(state.claimSessionConfig(rival))
    state.releaseSessionConfig(winner)
    assertTrue(state.claimSessionConfig(rival))
    state.close()
  }
}
