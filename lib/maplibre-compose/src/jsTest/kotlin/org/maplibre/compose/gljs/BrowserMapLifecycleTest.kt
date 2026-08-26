package org.maplibre.compose.gljs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class BrowserMapLifecycleTest {

  private val style = BaseStyle.Json("""{"version":8,"name":"a","sources":{},"layers":[]}""")

  private val otherStyle =
    BaseStyle.Json(
      """{"version":8,"name":"b","sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""
    )

  @Test
  fun a_map_can_be_taken_out_of_the_composition() = runBrowserMapTest {
    var visible by mutableStateOf(true)
    var loaded = false
    setBrowserMapContent {
      if (visible) {
        MaplibreMap(
          state = rememberMapState(baseStyle = style),
          modifier = Modifier,
          onMapLoadFinished = { loaded = true },
        )
      }
    }
    waitUntilMap("the map to load") { loaded }

    visible = false
    waitForIdle()
    repeat(5) {
      yieldToBrowser()
      waitForIdle()
    }
  }

  @Test
  fun a_map_can_be_taken_out_and_put_back() = runBrowserMapTest {
    var visible by mutableStateOf(true)
    var loads = 0
    setBrowserMapContent {
      if (visible) {
        MaplibreMap(
          state = rememberMapState(baseStyle = style),
          modifier = Modifier,
          onMapLoadFinished = { loads += 1 },
        )
      }
    }
    waitUntilMap("the first map to load") { loads >= 1 }

    visible = false
    repeat(5) {
      yieldToBrowser()
      waitForIdle()
    }
    visible = true
    waitUntilMap("the replacement map to load") { loads >= 2 }
  }

  @Test
  fun changing_density_rebuilds_the_map_and_keeps_its_camera() = runBrowserMapTest {
    var density by mutableStateOf(Density(1f))
    var loads = 0
    val expectedCamera =
      CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 8.0)
    val mapState = MapState(cameraPosition = expectedCamera)
    mapState.baseStyle = style

    setBrowserMapContent {
      CompositionLocalProvider(LocalDensity provides density) {
        MaplibreMap(state = mapState, modifier = Modifier, onMapLoadFinished = { loads += 1 })
      }
    }
    waitUntilMap("the first map to load") { loads >= 1 }
    val firstViewport = mapState.viewport

    density = Density(2f)
    waitUntilMap("the replacement map to load at the new density") { loads >= 2 }

    assertNotSame(firstViewport, mapState.viewport, "the camera should attach to a new map")
    assertEquals(expectedCamera.target, mapState.camera.target, "camera target")
    assertEquals(expectedCamera.zoom, mapState.camera.zoom, 0.001, "camera zoom")
  }

  @Test
  fun switching_the_base_style_reports_loading_finished_again() = runBrowserMapTest {
    var current by mutableStateOf(style)
    var loads = 0
    setBrowserMapContent {
      MaplibreMap(
        state = rememberMapState(baseStyle = current),
        modifier = Modifier,
        onMapLoadFinished = { loads += 1 },
      )
    }
    waitUntilMap("the first style to load") { loads >= 1 }

    current = otherStyle
    waitUntilMap("the second style to report that it finished loading") { loads >= 2 }
    assertTrue(loads >= 2, "every style load should report finishing, not only the first")
  }
}
