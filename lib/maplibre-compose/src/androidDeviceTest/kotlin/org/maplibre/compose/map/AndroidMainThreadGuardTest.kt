package org.maplibre.compose.map

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle

class AndroidMainThreadGuardTest {
  @Test
  fun a_background_created_runtime_can_present_in_the_current_main_thread_turn() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var runtime: MapRuntime
    lateinit var state: MapState
    val owner = MapPresentationOwnerToken()

    instrumentation.runOnMainSync {
      // Joining keeps this Looper turn ahead of the guard's queued initialization callback.
      thread { runtime = mapRuntimeForTest(mainDispatcher = Dispatchers.Main.immediate) }.join()
      state = runtime.createMapState(BaseStyle.Demo)
      assertFalse(state.lifecycle.isPresentedByOtherOwner(owner))
      state.reservePresentation(owner)
      state.setCameraPosition(CameraPosition(zoom = 2.0))
      assertEquals(2.0, state.cameraPosition.zoom)
    }

    instrumentation.runOnMainSync {
      try {
        // The queued initialization has now run; it must agree with the earlier access.
        state.setCameraPosition(CameraPosition(zoom = 3.0))
        assertEquals(3.0, state.cameraPosition.zoom)
      } finally {
        state.close()
        runtime.close()
      }
    }
  }
}
