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
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.skiko.HostOperatingSystem
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.style.BaseStyle

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
      // This host never gets a GPU context, so its frame-clock retries never become idle.
      mainClock.autoAdvance = false
      val runtime = createNativeMapRuntime(runtimeOptions)
      val state = runtime.createMapState(baseStyle = BaseStyle.Empty)
      var host by
        mutableStateOf(
          contextlessPresentationHost("same"),
          referentialEqualityPolicy(),
        )

      setContent {
        ProvideMapPresentationHost(host) {
          MaplibreMap(state = state)
        }
      }
      waitUntil(timeoutMillis = 10_000) {
        mainClock.advanceTimeByFrame()
        state.currentMapAttachment != null
      }
      val firstPresentation = requireNotNull(state.currentMapAttachment)
      val engine = firstPresentation.adapter

      runOnIdle { host = contextlessPresentationHost("same") }
      waitUntil(timeoutMillis = 10_000) {
        mainClock.advanceTimeByFrame()
        state.currentMapAttachment != null && state.currentMapAttachment !== firstPresentation
      }

      assertTrue(!firstPresentation.isValid)
      assertNotSame(firstPresentation, state.currentMapAttachment)
      assertSame(engine, requireNotNull(state.currentMapAttachment).adapter)
      assertSame(runtime, state.runtime)
      assertTrue(!runtime.isClosed)
      assertTrue(!state.isClosed)

      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun inspection_mode_does_not_require_a_presentation_host() = runFfiComposeUiTest {
    val runtime = createNativeMapRuntime(runtimeOptions)
    val state = runtime.createMapState(baseStyle = BaseStyle.Empty)

    setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) { MaplibreMap(state = state) }
    }

    waitForIdle()
    assertTrue(state.currentMapAttachment == null)
    runtime.close()
    runtime.awaitClosed()
  }

  private fun contextlessPresentationHost(description: String): ComposeMapPresentationHost =
    when (HostOperatingSystem.current()) {
      HostOperatingSystem.Macos ->
        ComposeMapPresentationHost.macosMetal(description, { null }, { it.run() })
      HostOperatingSystem.Windows ->
        ComposeMapPresentationHost.windowsDirect3d12(description, { null }, { it.run() })
      HostOperatingSystem.Linux ->
        ComposeMapPresentationHost.linuxOpenGl(description, { null }, { it.run() })
      HostOperatingSystem.Unsupported -> error("Unsupported test platform")
    }
}
