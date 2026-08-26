package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.gljs.yieldToBrowser
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * The session leaves and re-enters the composition against the same [MapState]: the browser map is
 * recreated per composition, and the recorded style and camera replay into it.
 */
@OptIn(ExperimentalTestApi::class)
class BrowserMapReattachTest {

  private val style =
    BaseStyle.Json(
      """{"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""
    )

  @Test
  fun reattaching_recreates_the_map_and_replays_the_style_and_camera() = runBrowserMapTest {
    var attached by mutableStateOf(true)
    var loads = 0
    val errors = mutableListOf<String>()
    val firstPosition =
      CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 8.0)
    lateinit var state: MapState

    setBrowserMapContent {
      val mapState = rememberMapState(cameraPosition = firstPosition, baseStyle = style)
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

    waitUntilMap("the first map to load") { loads >= 1 }
    val firstAdapter = assertNotNull(state.cameraState.map, "no adapter after the first attach")

    attached = false
    waitUntilMap("the session to detach") { state.cameraState.map == null }
    repeat(3) {
      yieldToBrowser()
      waitForIdle()
    }

    attached = true
    waitUntilMap("the replacement map to load the recorded style") { loads >= 2 }
    val secondAdapter = assertNotNull(state.cameraState.map, "no adapter after the re-attach")
    assertNotSame(firstAdapter, secondAdapter, "the browser map is recreated per composition")

    waitUntilMap("the recorded camera to replay into the new map") {
      val camera = secondAdapter.getCameraPosition()
      abs(camera.target.longitude - firstPosition.target.longitude) < 0.001 &&
        abs(camera.target.latitude - firstPosition.target.latitude) < 0.001 &&
        abs(camera.zoom - firstPosition.zoom) < 0.001
    }
    assertEquals(firstPosition.target, state.camera.target, "camera target")
    assertEquals(firstPosition.zoom, state.camera.zoom, 0.001, "camera zoom")
    assertTrue(errors.isEmpty(), "the cycle reported errors: $errors")
  }
}
