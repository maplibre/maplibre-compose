package org.maplibre.compose.map

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding

class MainThreadGuardTest {
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
