package org.maplibre.compose.map

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

@OptIn(ExperimentalTestApi::class)
class DesktopPresentationHostLifetimeTest {
  private val cacheFile = FfiTestPlatform.createCacheFile()
  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun replacing_a_compatible_presentation_host_keeps_the_runtime_logical_map_and_engine() =
    runFfiComposeUiTest {
      MlnFfiApplication.configure(runtimeOptions)
      val runtime = createNativeMapRuntime(runtimeOptions)
      val state = runtime.createMapState(initialBaseStyle = BaseStyle.Empty)
      var host by
        mutableStateOf(
          ContextlessPresentationHost("first", equalityKey = "same"),
          referentialEqualityPolicy(),
        )

      setContent {
        ProvideMapPresentationHost(host) {
          MaplibreMap(state)
        }
      }
      waitUntil(timeoutMillis = 10_000) { state.presentation != null }
      val firstPresentation = requireNotNull(state.presentation)
      val engine = firstPresentation.adapter

      runOnIdle { host = ContextlessPresentationHost("second", equalityKey = "same") }
      waitUntil(timeoutMillis = 10_000) {
        state.presentation != null && state.presentation !== firstPresentation
      }

      assertTrue(!firstPresentation.isValid)
      assertNotSame(firstPresentation, state.presentation)
      assertSame(engine, requireNotNull(state.presentation).adapter)
      assertSame(runtime, state.runtime)
      assertTrue(!runtime.isClosed)
      assertTrue(!state.isClosed)

      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun inspection_mode_does_not_require_a_presentation_host() = runFfiComposeUiTest {
    val runtime = createNativeMapRuntime(runtimeOptions)
    val state = runtime.createMapState(initialBaseStyle = BaseStyle.Empty)

    setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) { MaplibreMap(state) }
    }

    waitForIdle()
    assertTrue(state.presentation == null)
    runtime.close()
    runtime.awaitClosed()
  }

  private class ContextlessPresentationHost(
    private val name: String,
    private val equalityKey: String,
  ) : ComposeMapPresentationHost {
    override val description: String = "$name contextless presentation host"
    override val backend: ComposeRenderBackend = packagedComposeBackend()

    override fun gpuContext(): ComposeGpuContext? = null

    override fun runOnGpuThread(action: Runnable) {
      action.run()
    }

    override fun equals(other: Any?): Boolean =
      other is ContextlessPresentationHost && equalityKey == other.equalityKey

    override fun hashCode(): Int = equalityKey.hashCode()
  }

  private companion object {
    fun packagedComposeBackend(): ComposeRenderBackend =
      when (Maplibre.supportedRenderBackends().single()) {
        RenderBackend.METAL -> ComposeRenderBackend.METAL
        RenderBackend.VULKAN -> {
          if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            ComposeRenderBackend.DIRECT3D12
          } else {
            ComposeRenderBackend.OPENGL
          }
        }
        else -> error("No Desktop presentation host for the packaged runtime")
      }
  }
}
