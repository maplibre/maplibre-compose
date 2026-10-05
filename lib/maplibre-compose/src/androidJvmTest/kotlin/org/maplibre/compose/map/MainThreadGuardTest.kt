package org.maplibre.compose.map

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding

class MainThreadGuardTest {
  @Test
  fun a_background_created_runtime_can_present_before_its_main_dispatcher_queue_runs() {
    val main = TestMainDispatcher()
    lateinit var runtime: MapRuntime
    thread { runtime = mapRuntimeForTest(mainDispatcher = main) }.join()
    val state = runtime.createMapState(BaseStyle.Demo)
    val owner = MapPresentationOwnerToken()

    try {
      // Composition can reach this check in the current main-thread turn, ahead of queued work.
      assertFalse(state.lifecycle.isPresentedByOtherOwner(owner))
      state.reservePresentation(owner)
      state.setCameraPosition(CameraPosition(zoom = 2.0))
      assertEquals(2.0, state.cameraPosition.zoom)

      main.drain()
      state.setCameraPosition(CameraPosition(zoom = 3.0))
      assertEquals(3.0, state.cameraPosition.zoom)
    } finally {
      // Also lets cleanup run when the first access fails on an unfixed guard.
      main.drain()
      state.close()
      runtime.close()
    }
  }

  @Test
  fun a_background_first_access_cannot_claim_the_main_thread() {
    val main = TestMainDispatcher()
    lateinit var guard: MainThreadGuard
    var failure: Throwable? = null
    thread {
      guard = MainThreadGuard(main)
      failure = runCatching { guard.requireMain() }.exceptionOrNull()
    }
      .join()

    assertIs<IllegalStateException>(failure)
    guard.requireMain()
    main.drain()
    guard.requireMain()

    thread { failure = runCatching { guard.requireMain() }.exceptionOrNull() }.join()
    assertIs<IllegalStateException>(failure)
  }

  @Test
  fun map_state_rejects_a_mutation_from_another_thread() {
    // The test thread is the main thread here; the default helper opts out of confinement.
    val runtime = mapRuntimeForTest(mainDispatcher = TestMainDispatcher())
    val state = runtime.createMapState(BaseStyle.Demo)
    state.setCameraPosition(CameraPosition(zoom = 2.0))

    var failure: Throwable? = null
    thread {
      failure =
        runCatching { state.setCameraPosition(CameraPosition(zoom = 3.0)) }.exceptionOrNull()
    }
      .join()

    val error = assertIs<IllegalStateException>(failure)
    assertTrue(error.message.orEmpty().contains("main thread"))
    state.close()
    runtime.close()
  }

  @Test
  fun a_style_handle_operation_from_another_thread_is_rejected_before_it_runs() {
    val runtime = mapRuntimeForTest(mainDispatcher = TestMainDispatcher())
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = PresentationTestAdapter()
    state.publishPresentation(state.reservePresentation(), adapter)
    val binding = RecordingStyleBinding()
    assertTrue(state.styleAuthority.updateLoadedStyle(adapter, binding))

    var ran = false
    var failure: Throwable? = null
    thread {
      failure =
        runCatching { state.styleAuthority.runStyleHandleOperation(binding) { ran = true } }
          .exceptionOrNull()
    }
      .join()

    val error = assertIs<IllegalStateException>(failure)
    assertTrue(error.message.orEmpty().contains("main thread"))
    assertFalse(ran)
    state.close()
    runtime.close()
  }
}
