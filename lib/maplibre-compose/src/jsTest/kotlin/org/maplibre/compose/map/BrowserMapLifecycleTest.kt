package org.maplibre.compose.map

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
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.gljs.yieldToBrowser
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

  /**
   * One composed map walks the composition lifecycle: it loads, a base-style switch reports loading
   * finished again, leaving the composition survives, and returning loads a replacement.
   */
  @Test
  fun a_map_switches_styles_leaves_the_composition_and_a_replacement_loads() = runBrowserMapTest {
    var visible by mutableStateOf(true)
    var current by mutableStateOf(style)
    var loads = 0
    setBrowserMapContent {
      if (visible) {
        MaplibreMap(
          state = rememberMapState(baseStyle = current),
          modifier = Modifier,
          onMapLoadFinished = { loads += 1 },
        )
      }
    }
    waitUntilMap("the first style to load") { loads >= 1 }

    current = otherStyle
    waitUntilMap("the switched style to report that it finished loading, not only the first") {
      loads >= 2
    }

    visible = false
    waitForIdle()
    repeat(5) {
      yieldToBrowser()
      waitForIdle()
    }

    visible = true
    waitUntilMap("the replacement map to load after re-entering the composition") { loads >= 3 }
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
}
