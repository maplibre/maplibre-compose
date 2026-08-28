package org.maplibre.compose.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setMultiUseFfiTestMapContent
import org.maplibre.compose.style.BaseStyle

/** [MaplibreMap] is keyed on its state: a different state gets a fresh session subtree. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapStateSwapTest {

  private val cache = FfiTestCache()

  @AfterTest
  fun cleanUp() {
    cache.close()
  }

  @Test
  fun recomposing_with_a_second_state_detaches_the_first_and_attaches_the_second() =
    runFfiComposeUiTest {
      cache.configure()
      var useSecond by mutableStateOf(false)
      lateinit var stateA: MapState
      lateinit var stateB: MapState

      setMultiUseFfiTestMapContent {
        stateA = rememberMapState(baseStyle = STYLE, initialCameraPosition = START_POSITION)
        stateB = rememberMapState(baseStyle = STYLE)
        Box(Modifier.fillMaxSize()) {
          MaplibreMap(
            state = if (useSecond) stateB else stateA,
            modifier = Modifier.fillMaxSize(),
            logger = null,
          )
        }
      }

      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { stateA.isAttached }
      assertFalse(stateB.isAttached, "the unshown state has no session")

      runOnUiThread { useSecond = true }
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { !stateA.isAttached && stateB.isAttached }

      assertFalse(stateA.isAttached, "the replaced state is detached")
      assertTrue(stateB.isAttached, "the new state is attached")
      assertEquals(
        START_POSITION.zoom,
        stateA.camera.zoom,
        0.01,
        "the detached state still reads its camera",
      )
    }

  @Test
  fun a_second_concurrent_maplibre_map_on_one_state_surfaces_an_error() = runFfiComposeUiTest {
    cache.configure()
    val state = MapState()
    try {
      val error =
        assertFailsWith<IllegalStateException> {
          setMultiUseFfiTestMapContent {
            Box(Modifier.fillMaxSize()) {
              MaplibreMap(state = state, modifier = Modifier.fillMaxSize(), logger = null)
              MaplibreMap(state = state, modifier = Modifier.fillMaxSize(), logger = null)
            }
          }
          waitForIdle()
        }
      assertTrue(
        "one MapState shows one MaplibreMap" in error.message.orEmpty(),
        "the error names the single-session contract: ${error.message}",
      )
    } finally {
      state.close()
    }
  }

  private companion object {
    const val SETTLE_TIMEOUT_MILLIS = 30_000L

    val START_POSITION = CameraPosition(zoom = 4.0)

    val STYLE =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background"}]}""")
  }
}
