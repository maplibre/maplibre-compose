package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  runComposeUiTest { block() }
}

@Composable
internal actual fun FfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  content: @Composable () -> Unit,
) {
  val factory = remember { CurrentRuntimeTestMapHostFactory() }
  CompositionLocalProvider(
    LocalMlnFfiMapHostFactory provides factory,
    LocalMlnFfiRuntimeOptions provides runtimeOptions,
    content = content,
  )
}

/** Creates a production bridge for whichever runtime this Desktop test process packages. */
private class CurrentRuntimeTestMapHostFactory : MlnFfiMapHostFactory {
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
    val driver = FfiTestPlatform.createRenderDriver()
    check(driver.backends.producer == producer) {
      "Packaged runtime created ${driver.backends.producer}, not requested $producer"
    }
    return MlnFfiMapHostResult.Created(driver)
  }

  private fun composeBackend(): ComposeRenderBackend =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
      ComposeRenderBackend.DIRECT3D12
    } else {
      ComposeRenderBackend.OPENGL
    }
}
