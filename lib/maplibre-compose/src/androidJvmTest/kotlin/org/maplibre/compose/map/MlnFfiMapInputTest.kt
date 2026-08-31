@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Camera effects that go through a live MapLibre Native map.
 *
 * Gesture recognition is in [MapInputRecognitionTest], which hosts [mapInput] against a recording
 * [GestureTarget] and does not create a map. Each method here creates one map and tears it down. A
 * blocked native frame pump never reaches the `waitUntil` timeout.
 */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapInputTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun a_drag_pans_the_map() = runInputTest { camera ->
    val before = camera.position.target.longitude
    onRoot().performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(80f, 0f), delayMillis = 50)
      release()
    }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.target.longitude != before }
  }

  @Test
  fun a_pinch_zooms_the_map() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        pinch(
          start0 = center - Offset(30f, 0f),
          start1 = center + Offset(30f, 0f),
          end0 = center - Offset(120f, 0f),
          end1 = center + Offset(120f, 0f),
          durationMillis = 200,
        )
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > START_ZOOM + 0.5 }
    }

  /** Composes a map, waits for it to render, focuses it with a click, then runs [body]. */
  private fun runInputTest(
    focusWithMouse: Boolean = true,
    body: ComposeUiTest.(PresentationCamera) -> Unit,
  ) = runFfiComposeUiTest {
    val frames = AtomicInt(0)
    val initialPosition = CameraPosition(target = Position(0.0, 0.0), zoom = START_ZOOM)
    lateinit var mapState: MapState

    setFfiTestMapContent(runtimeOptions) {
      mapState =
        rememberMapState(
          initialCameraPosition = initialPosition,
          initialBaseStyle = BaseStyle.Empty,
        )
      MaplibreMap(
        state = mapState,
        modifier = Modifier.fillMaxSize(),
        callbacks = MapPresentationCallbacks(onFrame = { frames.incrementAndFetch() }),
      )
    }

    try {
      waitUntil(timeoutMillis = TIMEOUT) { mapState.presentation?.viewport != null }
    } catch (timeout: ComposeTimeoutException) {
      throw AssertionError(
        "the map never published a viewport within ${TIMEOUT}ms " +
          "(presentation=${mapState.presentation != null})",
        timeout,
      )
    }
    val camera = PresentationCamera(mapState)
    camera.position = initialPosition
    waitUntil(timeoutMillis = TIMEOUT) { frames.load() > 0 }
    waitUntil(timeoutMillis = TIMEOUT) {
      kotlin.math.abs(camera.position.zoom - START_ZOOM) < 0.001
    }

    if (focusWithMouse) onRoot().performMouseInput { click(Offset(10f, 10f)) }

    body(camera)
  }

  /** Keeps input-test assertions compact while routing every mutation through the live lease. */
  private class PresentationCamera(private val state: MapState) {
    private val presentation: MapPresentation
      get() = requireNotNull(state.presentation)

    var position: CameraPosition
      get() = state.cameraPosition
      set(value) {
        presentation.setCameraPosition(value)
      }
  }

  private companion object {
    const val TIMEOUT = 30_000L
    const val START_ZOOM = 4.0
  }
}
