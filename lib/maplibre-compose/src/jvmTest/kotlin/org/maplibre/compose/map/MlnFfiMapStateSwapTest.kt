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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setMultiUseFfiTestMapContent
import org.maplibre.compose.style.BaseStyle

/** Swapping the state argument of a composed [MaplibreMap] moves the session between states. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapStateSwapTest {

  private val cache = FfiTestCache()

  @AfterTest
  fun cleanUp() {
    cache.close()
  }

  @Test
  fun swapping_the_state_detaches_the_old_state_and_attaches_the_new_one() = runFfiComposeUiTest {
    cache.configure()
    var useSecond by mutableStateOf(false)
    lateinit var stateA: MapState
    lateinit var stateB: MapState

    setMultiUseFfiTestMapContent {
      stateA = rememberMapState(baseStyle = STYLE)
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
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      !stateA.isAttached && stateB.isAttached
    }

    assertFalse(stateA.isAttached, "the swapped-away state is detached")
    assertTrue(stateB.isAttached, "the swapped-in state is attached")
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

    val STYLE =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background"}]}""")
  }
}
