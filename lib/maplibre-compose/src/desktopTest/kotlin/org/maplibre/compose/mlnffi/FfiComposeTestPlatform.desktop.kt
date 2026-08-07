package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import java.awt.EventQueue
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  runComposeUiTest { block() }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  content: @Composable () -> Unit,
) {
  val preparedFactory = CurrentRuntimeTestMapHostFactory.prepare()
  try {
    setContent {
      CompositionLocalProvider(
        LocalMlnFfiMapHostFactory provides preparedFactory,
        LocalMlnFfiMapSurfaceStateObserver provides ::failOnUnusableSurface,
        LocalMlnFfiRuntimeOptions provides runtimeOptions,
        content = content,
      )
    }
    preparedFactory.requireConsumed()
  } catch (error: Throwable) {
    preparedFactory.closePendingDriver()
    throw error
  }
}

private fun failOnUnusableSurface(state: MlnFfiMapSurfaceState) {
  when (state) {
    is MlnFfiMapSurfaceState.Failed -> throw AssertionError(state.diagnostic, state.cause)
    is MlnFfiMapSurfaceState.Unavailable -> throw AssertionError(state.diagnostic)
    MlnFfiMapSurfaceState.Initializing,
    is MlnFfiMapSurfaceState.Ready -> Unit
  }
}

/** Creates a production bridge for whichever runtime this Desktop test process packages. */
private class CurrentRuntimeTestMapHostFactory
private constructor(private var preparedDriver: FfiTestRenderDriver?) : MlnFfiMapHostFactory {
  override val supportedBackends: Set<RenderBackendPair> =
    Maplibre.supportedRenderBackends().mapTo(mutableSetOf()) {
      when (it) {
        RenderBackend.METAL -> RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)
        RenderBackend.VULKAN -> RenderBackendPair(MapRenderBackend.VULKAN, composeBackend())
        else -> error("No Desktop test map host for $it")
      }
    }

  override val description: String = "production ${supportedBackends.single()} test bridge"

  override fun create(producer: MapRenderBackend): MlnFfiMapHostResult {
    val driver =
      preparedDriver
        ?: return MlnFfiMapHostResult.Failed(
          "The prepared Desktop test bridge was already consumed; each test map may create one host"
        )
    if (driver.backends.producer != producer) {
      closePendingDriver()
      return MlnFfiMapHostResult.Failed(
        "Packaged runtime created ${driver.backends.producer}, not requested $producer"
      )
    }
    preparedDriver = null
    return MlnFfiMapHostResult.Created(driver)
  }

  fun closePendingDriver() {
    preparedDriver?.close()
    preparedDriver = null
  }

  fun requireConsumed() {
    if (preparedDriver == null) return
    closePendingDriver()
    error("The test content did not create a Desktop map host during initial composition")
  }

  private fun composeBackend(): ComposeRenderBackend =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
      ComposeRenderBackend.DIRECT3D12
    } else {
      ComposeRenderBackend.OPENGL
    }

  companion object {
    fun prepare(): CurrentRuntimeTestMapHostFactory {
      check(!EventQueue.isDispatchThread()) {
        "The Desktop test bridge must be prepared off the EDT"
      }
      return CurrentRuntimeTestMapHostFactory(FfiTestPlatform.createRenderDriver())
    }
  }
}
