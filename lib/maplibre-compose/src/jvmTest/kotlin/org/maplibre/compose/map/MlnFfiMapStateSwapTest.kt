package org.maplibre.compose.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

/** Swapping the state argument of a composed [MaplibreMap] moves the session between states. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapStateSwapTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun swapping_the_state_detaches_the_old_state_and_attaches_the_new_one() = runFfiComposeUiTest {
    MlnFfiApplication.configure(runtimeOptions)
    var useSecond by mutableStateOf(false)
    lateinit var stateA: MapState
    lateinit var stateB: MapState

    setContent {
      val factory = remember { MultiUseSwapTestMapHostFactory() }
      CompositionLocalProvider(LocalMlnFfiMapHostFactory provides factory) {
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
    }

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { stateA.attachedAdapter != null }
    assertNull(stateB.attachedAdapter, "the unshown state has no session")

    runOnUiThread { useSecond = true }
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      stateA.attachedAdapter == null && stateB.attachedAdapter != null
    }

    assertNull(stateA.attachedAdapter, "the swapped-away state is detached")
    assertNotNull(stateB.attachedAdapter, "the swapped-in state is attached")
  }

  @Test
  fun a_second_concurrent_maplibre_map_on_one_state_surfaces_an_error() = runFfiComposeUiTest {
    MlnFfiApplication.configure(runtimeOptions)
    val state = MapState()
    try {
      val error =
        assertFailsWith<IllegalStateException> {
          setContent {
            val factory = remember { MultiUseSwapTestMapHostFactory() }
            CompositionLocalProvider(LocalMlnFfiMapHostFactory provides factory) {
              Box(Modifier.fillMaxSize()) {
                MaplibreMap(state = state, modifier = Modifier.fillMaxSize(), logger = null)
                MaplibreMap(state = state, modifier = Modifier.fillMaxSize(), logger = null)
              }
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

  /** The production Desktop test bridge is one-shot; a re-entering surface needs a fresh driver. */
  private class MultiUseSwapTestMapHostFactory : MlnFfiMapHostFactory {
    override val bridges: List<RenderBackendPair> =
      listOf(
        when (Maplibre.supportedRenderBackends().singleOrNull()) {
          RenderBackend.METAL ->
            RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)
          RenderBackend.VULKAN -> RenderBackendPair(MapRenderBackend.VULKAN, composeBackend())
          else -> error("No multi-use Desktop test bridge for this runtime")
        }
      )

    override val description: String = "multi-use ${bridges.single()} test bridge"

    override fun create(backends: RenderBackendPair): MlnFfiMapHostResult =
      MlnFfiMapHostResult.Created(FfiTestPlatform.createRenderDriver())

    private fun composeBackend(): ComposeRenderBackend =
      if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        ComposeRenderBackend.DIRECT3D12
      } else {
        ComposeRenderBackend.OPENGL
      }
  }

  private companion object {
    const val SETTLE_TIMEOUT_MILLIS = 30_000L

    val STYLE =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background"}]}""")
  }
}
