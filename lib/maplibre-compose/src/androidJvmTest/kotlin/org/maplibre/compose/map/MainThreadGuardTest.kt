package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding

class MainThreadGuardTest {
  @Test
  fun a_background_created_runtime_can_present_before_its_main_dispatcher_queue_runs() = runTest {
    // Nothing drains this main's queue, so the guard's queued pin has not run yet.
    val main = TestMainDispatcher()
    val runtime = withContext(Dispatchers.Default) { mapRuntimeForTest(mainDispatcher = main) }
    val state = runtime.createMapState(BaseStyle.Demo)
    val owner = MapPresentationOwnerToken()

    try {
      // Composition can reach this check in the current main-thread turn, ahead of queued work.
      assertFalse(state.lifecycle.isPresentedByOtherOwner(owner))
      state.reservePresentation(owner)
      state.setCameraPosition(CameraPosition(zoom = 2.0))
      assertEquals(2.0, state.cameraPosition.zoom)
    } finally {
      state.close()
      runtime.close()
    }
  }

  @Test
  fun a_background_first_access_cannot_claim_the_main_thread() = runTest {
    val main = testMainDispatcher()
    val (guard, failure) =
      withContext(Dispatchers.Default) {
        val guard = MainThreadGuard(main)
        guard to runCatching { guard.requireMain() }.exceptionOrNull()
      }

    assertIs<IllegalStateException>(failure)
    guard.requireMain()

    val laterFailure =
      withContext(Dispatchers.Default) { runCatching { guard.requireMain() }.exceptionOrNull() }
    assertIs<IllegalStateException>(laterFailure)
  }

  @Test
  fun map_state_rejects_a_mutation_from_another_thread() = runTest {
    val runtime = mapRuntimeForTest(mainDispatcher = TestMainDispatcher())
    val state = runtime.createMapState(BaseStyle.Demo)
    state.setCameraPosition(CameraPosition(zoom = 2.0))

    val failure =
      withContext(Dispatchers.Default) {
        runCatching { state.setCameraPosition(CameraPosition(zoom = 3.0)) }.exceptionOrNull()
      }

    val error = assertIs<IllegalStateException>(failure)
    assertTrue(error.message.orEmpty().contains("main thread"))
    state.close()
    runtime.close()
  }

  @Test
  fun a_style_handle_operation_from_another_thread_is_rejected_before_it_runs() = runTest {
    val runtime = mapRuntimeForTest(mainDispatcher = TestMainDispatcher())
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = PresentationTestAdapter()
    state.publishPresentation(state.reservePresentation(), adapter)
    val binding = RecordingStyleBinding()
    assertTrue(state.styleAuthority.updateLoadedStyle(adapter, binding))

    var ran = false
    val failure =
      withContext(Dispatchers.Default) {
        runCatching {
          state.styleAuthority.requireOpen()
          ran = true
        }
          .exceptionOrNull()
      }

    val error = assertIs<IllegalStateException>(failure)
    assertTrue(error.message.orEmpty().contains("main thread"))
    assertFalse(ran)
    state.close()
    runtime.close()
  }
}
