package org.maplibre.compose.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)
class MapOverlayInputTest {
  @Test
  fun child_claims_geometry_contacts_and_declines_background_to_ancestor_map() =
    runFfiComposeUiTest {
      fun point(x: Float, y: Float) = Offset(x * density.density, y * density.density)
      val cacheFile = FfiTestPlatform.createCacheFile()
      val options = MapRuntimeOptions(cacheFile = cacheFile, mainDispatcher = UnconfinedTestMain)
      try {
        withTestRuntime(options) { runtime ->
          val state =
            runtime.createMapState(BaseStyle.Empty, cameraPosition = CameraPosition(zoom = 12.0))
          var claims = 0
          var geometryPan = Offset.Zero
          var geometryZoom = 1f
          var geometryRotation = 0f
          var buttonClicks = 0
          var placements = 0
          var customHandler by mutableStateOf(true)
          setFfiTestMapContent(options) {
            MaplibreMap(
              modifier = Modifier.size(300.dp).testTag("map"),
              state = state,
              interactions =
                MapInteractions {
                  callbacks {
                    click {
                      onUnhandled {
                        placements++
                        ClickResult.Consume
                      }
                    }
                  }
                },
              overlay = {
                if (customHandler)
                  Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                      awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (
                          !down.isConsumed &&
                            down.position.x < 80.dp.toPx() &&
                            down.position.y < 80.dp.toPx()
                        ) {
                          claims++
                          down.consume()
                          do {
                            val event = awaitPointerEvent()
                            geometryPan += event.calculatePan()
                            geometryZoom *= event.calculateZoom()
                            geometryRotation += event.calculateRotation()
                            event.changes.forEach { it.consume() }
                          } while (event.changes.any { it.pressed })
                        }
                      }
                    }
                  )
                Box(Modifier.size(30.dp).testTag("button").clickable { buttonClicks++ })
              },
            )
          }
          waitUntil(timeoutMillis = 30_000L) {
            state.style.loadState == StyleLoadState.Ready &&
              state.currentMapAttachment?.viewport != null &&
              onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty()
          }
          val before = state.cameraPosition
          onNodeWithTag("map").performMouseInput {
            moveTo(point(60f, 60f))
            press()
            moveBy(point(40f, 0f))
            release()
          }
          waitForIdle()
          assertEquals(1, claims)
          assertTrue(geometryPan != Offset.Zero)
          assertEquals(before, state.cameraPosition)
          onNodeWithTag("button").performMouseInput { click(center) }
          waitForIdle()
          assertEquals(1, buttonClicks)
          assertEquals(1, claims)
          assertEquals(0, placements)
          onNodeWithTag("map").performMouseInput { exit() }
          onNodeWithTag("map").performTouchInput {
            down(0, point(60f, 60f))
            down(1, point(100f, 100f))
            moveTo(0, point(40f, 40f))
            moveTo(1, point(160f, 120f))
            up(0)
            up(1)
          }
          waitForIdle()
          assertEquals(2, claims)
          assertTrue(geometryZoom > 1f)
          assertTrue(geometryRotation != 0f)
          assertEquals(before, state.cameraPosition)
          onNodeWithTag("map").performMouseInput { click(point(200f, 200f)) }
          waitUntil(timeoutMillis = 5_000L) { placements == 1 }
          onNodeWithTag("map").performMouseInput {
            moveTo(point(200f, 200f))
            press()
            moveBy(point(40f, 0f))
            release()
          }
          waitUntil(timeoutMillis = 5_000L) { state.cameraPosition.target != before.target }
          assertEquals(1, placements)
          val zoomBefore = state.cameraPosition.zoom
          onNodeWithTag("map").performMouseInput { exit() }
          onNodeWithTag("map").performTouchInput {
            down(0, point(120f, 160f))
            down(1, point(180f, 160f))
            moveTo(0, point(100f, 160f))
            moveTo(1, point(200f, 160f))
            moveTo(0, point(80f, 160f))
            moveTo(1, point(220f, 160f))
            up(0)
            up(1)
          }
          waitUntil(timeoutMillis = 5_000L) { state.cameraPosition.zoom > zoomBefore }
          runOnIdle { customHandler = false }
          val defaultBefore = state.cameraPosition.target
          onNodeWithTag("map").performMouseInput {
            moveTo(point(200f, 200f))
            press()
            moveBy(point(40f, 0f))
            release()
          }
          waitUntil(timeoutMillis = 5_000L) { state.cameraPosition.target != defaultBefore }
        }
      } finally {
        FfiTestPlatform.deleteCacheFile(cacheFile)
      }
    }

  @Test
  fun stock_compose_detectors_claim_before_map_navigation() = runFfiComposeUiTest {
    fun point(x: Float, y: Float) = Offset(x * density.density, y * density.density)
    val cacheFile = FfiTestPlatform.createCacheFile()
    val options = MapRuntimeOptions(cacheFile = cacheFile, mainDispatcher = UnconfinedTestMain)
    try {
      withTestRuntime(options) { runtime ->
        val state =
          runtime.createMapState(BaseStyle.Empty, cameraPosition = CameraPosition(zoom = 12.0))
        var transformations = 0
        var drags = 0
        var transform by mutableStateOf(true)
        setFfiTestMapContent(options) {
          MaplibreMap(
            Modifier.size(300.dp).testTag("map"),
            state = state,
            interactions =
              MapInteractions { callbacks { click { onUnhandled { ClickResult.Consume } } } },
            overlay = {
              Box(
                Modifier.fillMaxSize().pointerInput(transform) {
                  if (transform) detectTransformGestures { _, _, _, _ -> transformations++ }
                  else
                    detectDragGestures { change, _ ->
                      change.consume()
                      drags++
                    }
                }
              )
            },
          )
        }
        waitUntil(timeoutMillis = 30_000L) {
          state.style.loadState == StyleLoadState.Ready &&
            state.currentMapAttachment?.viewport != null &&
            onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty()
        }
        val before = state.cameraPosition
        onNodeWithTag("map").performTouchInput {
          down(0, point(100f, 150f))
          down(1, point(200f, 150f))
          moveTo(0, point(60f, 120f))
          moveTo(1, point(240f, 180f))
          up(0)
          up(1)
        }
        waitForIdle()
        assertTrue(transformations > 0)
        assertEquals(before, state.cameraPosition)
        runOnIdle { transform = false }
        onNodeWithTag("map").performTouchInput {
          down(point(150f, 150f))
          repeat(20) { moveBy(point(2f, 0f)) }
          up()
        }
        waitForIdle()
        assertTrue(drags > 0)
        assertEquals(before, state.cameraPosition)
      }
    } finally {
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }
}
