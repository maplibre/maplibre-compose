package org.maplibre.compose.map

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle

class MainThreadGuardTest {
  @Test
  fun map_state_rejects_a_mutation_from_another_thread() {
    val runtime = mapRuntimeForTest()
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
}
