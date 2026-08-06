package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
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
import org.maplibre.compose.mlnffi.HeadlessVulkanMapHostFactory
import org.maplibre.compose.mlnffi.LocalMlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.LocalMlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

/** Keyboard and pointer input reach the map, and a click stays distinct from a drag. */
@OptIn(ExperimentalTestApi::class)
class DesktopMapInputTest {

  private val cacheDirectory = Files.createTempDirectory("maplibre-input-test")

  /** Every click the map reported, including the one [runInputTest] uses to take focus. */
  private val clicks = mutableListOf<Position>()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(
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
    // The zoom has to arrive, not merely start: a transition only advances while frames render.
    awaitZoom(camera, START_ZOOM + 1.0)

    onRoot().performKeyInput { pressKey(Key.Minus) }
    awaitZoom(camera, START_ZOOM)
  }

  @Test
  fun `double click zooms in`() = runInputTest { camera ->
    onRoot().performMouseInput { doubleClick() }
    awaitZoom(camera, START_ZOOM + 1.0)
  }

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

  @Test
  fun `shift and arrow keys rotate and tilt`() = runInputTest { camera ->
    onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.bearing > 0.0 }

    onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionUp) } }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.tilt > 0.0 }
  }

  /**
   * A press that moves a hair is still a click. The slop only earns its keep if it gates the start
   * of the drag: gating anything later leaves a press that jittered by a pixel dragging the map and
   * reporting nothing.
   */
  @Test
  fun `a press that jitters within the slop still clicks`() = runInputTest { camera ->
    val longitudeBefore = camera.position.target.longitude
    val clicksBefore = clicks.size

    onRoot().performMouseInput {
      // Away from the focus click, so this does not read as the second half of a double click.
      moveTo(Offset(width * 0.25f, height * 0.25f))
      press()
      moveBy(Offset(1f, 0f))
      release()
    }

    waitUntil(timeoutMillis = TIMEOUT) { clicks.size > clicksBefore }
    assertEquals(
      longitudeBefore,
      camera.position.target.longitude,
      TARGET_TOLERANCE,
      "the jitter panned the map",
    )
  }

  @Test
  fun `a press past the slop drags instead of clicking`() = runInputTest { camera ->
    val longitudeBefore = camera.position.target.longitude
    val clicksBefore = clicks.size

    onRoot().performMouseInput {
      moveTo(Offset(width * 0.25f, height * 0.25f))
      press()
      moveBy(Offset(60f, 0f))
      release()
    }

    waitUntil(timeoutMillis = TIMEOUT) { camera.position.target.longitude != longitudeBefore }
    assertEquals(clicksBefore, clicks.size, "the drag reported a click")
  }

  /** Waits for the camera to settle at [zoom], failing with the value it stopped at. */
  private fun androidx.compose.ui.test.ComposeUiTest.awaitZoom(camera: CameraState, zoom: Double) {
    waitUntil(timeoutMillis = TIMEOUT) { abs(camera.position.zoom - zoom) < ZOOM_TOLERANCE }
  }

  /** `PositionLocked` must still zoom, but without the pointer anchoring that would pan. */
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
    val factory = HeadlessVulkanMapHostFactory.create()
    lateinit var cameraState: CameraState

    setContent {
      CompositionLocalProvider(
        LocalMlnFfiMapHostFactory provides factory,
        LocalMlnFfiRuntimeOptions provides runtimeOptions,
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
          onMapClick = { position, _ ->
            clicks.add(position)
            ClickResult.Pass
          },
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
