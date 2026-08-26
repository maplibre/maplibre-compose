package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.gljs.yieldToBrowser
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleState
import org.maplibre.spatialk.geojson.Position

/**
 * The session leaves and re-enters the composition against the same internal [MapState]: the
 * browser map is recreated per composition, and the recorded style and camera replay into it.
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
    val cameraState = CameraState(firstPosition)
    val styleState = StyleState()

    setBrowserMapContent {
      val density = LocalDensity.current
      val layoutDirection = LocalLayoutDirection.current
      val mapState = remember {
        MapState(cameraState, styleState, density, layoutDirection, null, null)
      }
      DisposableEffect(mapState) { onDispose { mapState.close() } }
      LaunchedEffect(mapState) { mapState.startStyleComposition() }
      SideEffect {
        mapState.baseStyle = style
        mapState.onMapLoadFailed = { errors += "mapLoadFailed: $it" }
        mapState.onMapLoadFinished = { loads++ }
      }
      if (attached) {
        ComposableMapView(
          modifier = Modifier.fillMaxSize(),
          engine = mapState.engine,
          update = { map ->
            mapState.applyOptions(map, PaddingValues(0.dp), 0f..20f, 0f..60f, null, MapOptions())
            mapState.attachSession(map)
          },
          onReset = { mapState.detachSession() },
          logger = null,
          callbacks = mapState.callbacks,
          options = MapOptions(),
        )
      }
    }

    waitUntilMap("the first map to load") { loads >= 1 }
    val firstAdapter = assertNotNull(cameraState.map, "no adapter after the first attach")

    attached = false
    waitUntilMap("the session to detach") { cameraState.map == null }
    repeat(3) {
      yieldToBrowser()
      waitForIdle()
    }

    attached = true
    waitUntilMap("the replacement map to load the recorded style") { loads >= 2 }
    val secondAdapter = assertNotNull(cameraState.map, "no adapter after the re-attach")
    assertNotSame(firstAdapter, secondAdapter, "the browser map is recreated per composition")

    waitUntilMap("the recorded camera to replay into the new map") {
      val camera = secondAdapter.getCameraPosition()
      abs(camera.target.longitude - firstPosition.target.longitude) < 0.001 &&
        abs(camera.target.latitude - firstPosition.target.latitude) < 0.001 &&
        abs(camera.zoom - firstPosition.zoom) < 0.001
    }
    assertEquals(firstPosition.target, cameraState.position.target, "camera target")
    assertEquals(firstPosition.zoom, cameraState.position.zoom, 0.001, "camera zoom")
    assertTrue(errors.isEmpty(), "the cycle reported errors: $errors")
  }
}
