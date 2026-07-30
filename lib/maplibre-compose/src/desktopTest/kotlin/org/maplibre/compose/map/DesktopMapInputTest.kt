package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    onRoot().performKeyInput { pressKey(Key.Equals) }
    // A step is a doubling, which is one zoom level, and it has to arrive rather than merely
    // start: a transition only advances while frames render, so this fails if the loop stops
    // asking for them mid-animation.
    awaitZoom(camera, START_ZOOM + 1.0)

    onRoot().performKeyInput { pressKey(Key.Minus) }
    awaitZoom(camera, START_ZOOM)
  }

  @Test
  fun `double click zooms in`() = runInputTest { camera ->
    onRoot().performMouseInput { doubleClick() }
    awaitZoom(camera, START_ZOOM + 1.0)
  }

  /**
   * Discrete input eases rather than jumps, which is what every other MapLibre target does.
   *
   * Observed by sampling: an instant camera command is only ever seen at its destination, while an
   * eased one is caught somewhere in between.
   */
  @Test
  fun `double click eases rather than jumping`() = runInputTest { camera ->
    val target = START_ZOOM + 1.0
    var sawIntermediate = false

    onRoot().performMouseInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) {
      val zoom = camera.position.zoom
      if (zoom > START_ZOOM + 0.01 && zoom < target - 0.01) sawIntermediate = true
      zoom >= target - ZOOM_TOLERANCE
    }

    assertTrue(sawIntermediate, "the zoom went straight to $target, so it did not animate")
  }

  /** Waits for the camera to settle at [zoom], failing with the value it stopped at. */
  private fun androidx.compose.ui.test.ComposeUiTest.awaitZoom(camera: CameraState, zoom: Double) {
    waitUntil(timeoutMillis = TIMEOUT) { abs(camera.position.zoom - zoom) < ZOOM_TOLERANCE }
  }

  /**
   * `PositionLocked` promises rotation, tilt and zoom while the position stays put.
   *
   * A pointer-anchored zoom moves the camera target to keep the cursor's point still, so it is a
   * way to pan with the pointer — which this preset is meant to forbid. Zoom has to keep working.
   */
  @Test
  fun `position locked zooms without moving the camera`() =
    runInputTest(gestures = GestureOptions.PositionLocked) { camera ->
      val before = camera.position
      // Off centre, so an anchored zoom would visibly drag the target toward it.
      onRoot().performMouseInput { doubleClick(Offset(width * 0.2f, height * 0.2f)) }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > before.zoom }

      val after = camera.position
      assertEquals(before.target.longitude, after.target.longitude, TARGET_TOLERANCE, "longitude")
      assertEquals(before.target.latitude, after.target.latitude, TARGET_TOLERANCE, "latitude")
    }

  /** Composes a map, waits for it to render, focuses it with a click, then runs [body]. */
  private fun runInputTest(
    gestures: GestureOptions = GestureOptions.Standard,
    body: androidx.compose.ui.test.ComposeUiTest.(CameraState) -> Unit,
  ) = runComposeUiTest {
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
          options = MapOptions(gestureOptions = gestures),
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

    /** A zoom about the centre still drifts the target a hair through the projection. */
    const val TARGET_TOLERANCE = 1e-6

    const val ZOOM_TOLERANCE = 0.001
  }
}
