@file:OptIn(DelicateMapApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.gljs.yieldToBrowser
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleNode
import org.maplibre.spatialk.geojson.Position

/**
 * One hoisted [MapState] walks a full browser session cycle: [rememberMapState] carries style
 * content and a pre-attach camera into the first attach, [MapState.withPlatformMap] serves the live
 * map while composed and refuses while detached, and a re-attach recreates the browser map and
 * replays the recorded style and camera into it.
 */
@OptIn(ExperimentalTestApi::class)
class BrowserMapSessionCycleTest {

  private val style =
    BaseStyle.Json(
      """{"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""
    )

  @Test
  fun a_session_attaches_serves_the_platform_map_detaches_and_reattaches() = runBrowserMapTest {
    var attached by mutableStateOf(true)
    var loads = 0
    val errors = mutableListOf<String>()
    var node: StyleNode? = null
    lateinit var state: MapState

    setBrowserMapContent {
      val mapState =
        rememberMapState(initialCameraPosition = FIRST_POSITION, baseStyle = style) {
          node = LocalStyleNode.current
          BackgroundLayer(id = "state-entry-bg", color = const(Color.Red))
        }
      state = mapState
      if (attached) {
        MaplibreMap(
          state = mapState,
          modifier = Modifier.fillMaxSize(),
          logger = null,
          onMapLoadFailed = { errors += "mapLoadFailed: $it" },
          onMapLoadFinished = { loads++ },
        )
      }
    }

    // Step 1: the first attach loads the composed content at the pre-attach camera.
    waitUntilMap("the map to load with the composed layer") {
      loads >= 1 && node?.binding?.getLayer("state-entry-bg") != null
    }
    waitUntilMap("the pre-attach camera position to apply at attach") {
      state.attachedAdapter?.getCameraPosition()?.isNear(FIRST_POSITION) ?: false
    }
    val firstAdapter = assertNotNull(state.attachedAdapter, "no adapter after the first attach")

    // Step 2: the live platform map is served while a session is composed.
    val layerIds = state.withPlatformMap { it.getLayersOrder().toList() }
    assertTrue(
      "state-entry-bg" in layerIds,
      "the live map must report the composed layer, got: $layerIds",
    )

    // Step 3: the detached state refuses the platform map by naming the rule.
    attached = false
    waitUntilMap("the session to detach") { !state.isAttached }
    repeat(3) {
      yieldToBrowser()
      waitForIdle()
    }
    val failure = runCatching { state.withPlatformMap {} }.exceptionOrNull()
    val message = assertNotNull(assertIs<IllegalStateException>(failure).message)
    assertTrue(
      "detached" in message,
      "the message must state the no-live-map-while-detached rule, got: $message",
    )

    // Step 4: a re-attach recreates the browser map and replays the recorded style and camera.
    attached = true
    waitUntilMap("the replacement map to load the recorded style") { loads >= 2 }
    val secondAdapter = assertNotNull(state.attachedAdapter, "no adapter after the re-attach")
    assertNotSame(firstAdapter, secondAdapter, "the browser map is recreated per composition")

    waitUntilMap("the recorded camera to replay into the new map") {
      secondAdapter.getCameraPosition().isNear(FIRST_POSITION)
    }
    assertEquals(FIRST_POSITION.target, state.camera.target, "camera target")
    assertEquals(FIRST_POSITION.zoom, state.camera.zoom, 0.001, "camera zoom")
    assertTrue(errors.isEmpty(), "the cycle reported errors: $errors")
  }

  private fun CameraPosition.isNear(other: CameraPosition): Boolean =
    abs(zoom - other.zoom) < 0.01 &&
      abs(target.longitude - other.target.longitude) < 0.01 &&
      abs(target.latitude - other.target.latitude) < 0.01

  private companion object {
    val FIRST_POSITION =
      CameraPosition(target = Position(longitude = 11.39085, latitude = 47.26266), zoom = 6.0)
  }
}
