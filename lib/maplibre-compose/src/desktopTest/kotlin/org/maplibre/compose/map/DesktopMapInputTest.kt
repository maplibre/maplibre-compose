package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import co.touchlab.kermit.Logger
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.HeadlessVulkanMapHostFactory
import org.maplibre.compose.desktop.LocalDesktopMapHostFactory
import org.maplibre.compose.desktop.LocalDesktopRuntimeOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Keyboard and double-click input reach the map.
 *
 * The keyboard handling existed and was unreachable: key events only go to a focused node, and
 * nothing made the map focusable or ever asked for focus, so arrow keys and +/- did nothing at all.
 * Double-click zoom was never written, though its option and its zoom step both were. Both are
 * things a unit test catches and a compiler never will.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopMapInputTest {

  private val cacheDirectory = Files.createTempDirectory("maplibre-input-test")

  private val runtimeOptions =
    DesktopRuntimeOptions(
      cachePath = cacheDirectory.resolve("cache.db"),
      maximumCacheSizeBytes = null,
    )

  @AfterTest
  fun cleanUp() {
    cacheDirectory.toFile().deleteRecursively()
  }

  @Test
  fun `arrow keys pan the map`() = runInputTest { camera ->
    val before = camera.position.target.longitude
    onRoot().performKeyInput { pressKey(Key.DirectionRight) }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.target.longitude != before }
  }

  @Test
  fun `plus and minus zoom the map`() = runInputTest { camera ->
    val start = camera.position.zoom
    onRoot().performKeyInput { pressKey(Key.Equals) }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > start }

    val zoomedIn = camera.position.zoom
    onRoot().performKeyInput { pressKey(Key.Minus) }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom < zoomedIn }
  }

  @Test
  fun `double click zooms in`() = runInputTest { camera ->
    val before = camera.position.zoom
    onRoot().performMouseInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > before }
  }

  /** Composes a map, waits for it to render, focuses it with a click, then runs [body]. */
  private fun runInputTest(body: androidx.compose.ui.test.ComposeUiTest.(CameraState) -> Unit) =
    runComposeUiTest {
      val factory = HeadlessVulkanMapHostFactory.createOrNull() ?: return@runComposeUiTest
      lateinit var cameraState: CameraState

      setContent {
        CompositionLocalProvider(
          LocalDesktopMapHostFactory provides factory,
          LocalDesktopRuntimeOptions provides runtimeOptions,
        ) {
          cameraState =
            rememberCameraState(
              firstPosition = CameraPosition(target = Position(0.0, 0.0), zoom = START_ZOOM)
            )
          MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Empty,
            cameraState = cameraState,
            logger = Logger.withTag("input-test"),
          )
        }
      }

      waitUntil(timeoutMillis = TIMEOUT) { factory.created.isNotEmpty() }
      waitUntil(timeoutMillis = TIMEOUT) { factory.created.single().renderedFrames > 0 }
      waitUntil(timeoutMillis = TIMEOUT) {
        kotlin.math.abs(cameraState.position.zoom - START_ZOOM) < 0.001
      }

      // A click is what gives the map focus, so the keyboard cases depend on it too.
      onRoot().performMouseInput { click() }

      body(cameraState)
    }

  private companion object {
    const val TIMEOUT = 30_000L
    const val START_ZOOM = 4.0
  }
}
