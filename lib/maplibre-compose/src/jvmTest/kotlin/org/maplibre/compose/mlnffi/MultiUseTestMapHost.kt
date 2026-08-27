package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import org.maplibre.compose.map.LocalMlnFfiMapHostFactory
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

/** The production Desktop test bridge is one-shot; a re-entering surface needs a fresh driver. */
internal class MultiUseTestMapHostFactory : MlnFfiMapHostFactory {
  override val bridges: List<RenderBackendPair> =
    listOf(
      when (Maplibre.supportedRenderBackends().singleOrNull()) {
        RenderBackend.METAL -> RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)
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

/** Installs [content] under a [MultiUseTestMapHostFactory], for a surface that re-enters. */
@ExperimentalTestApi
internal fun ComposeUiTest.setMultiUseFfiTestMapContent(content: @Composable () -> Unit) {
  setContent {
    val factory = remember { MultiUseTestMapHostFactory() }
    CompositionLocalProvider(LocalMlnFfiMapHostFactory provides factory) { content() }
  }
}
