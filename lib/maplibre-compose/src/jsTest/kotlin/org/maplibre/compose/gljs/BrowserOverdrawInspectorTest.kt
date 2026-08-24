package org.maplibre.compose.gljs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.map.GlJsMapSession
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.style.BaseStyle

private val SPLIT_STYLE =
  BaseStyle.Json(
    """
    {
      "version": 8,
      "name": "overdraw",
      "sources": {
        "shape": {
          "type": "geojson",
          "data": {
            "type": "Feature",
            "properties": {},
            "geometry": {
              "type": "Polygon",
              "coordinates": [[[-180, -85], [0, -85], [0, 85], [-180, 85], [-180, -85]]]
            }
          }
        }
      },
      "layers": [
        {"id": "bg", "type": "background", "paint": {"background-color": "#0000ff"}},
        {"id": "shape", "type": "fill", "source": "shape", "paint": {"fill-color": "#ff0000"}}
      ]
    }
    """
      .trimIndent()
  )

@OptIn(ExperimentalTestApi::class)
class BrowserOverdrawInspectorTest {

  @Test
  fun toggling_overdraw_inspector_through_compose_draws_a_frame_with_the_flag() =
    runBrowserMapTest {
      var options by mutableStateOf(MapOptions())
      var loaded = false
      val cameraState = CameraState(CameraPosition())
      setBrowserMapContent {
        MaplibreMap(
          modifier = Modifier,
          baseStyle = SPLIT_STYLE,
          cameraState = cameraState,
          options = options,
          overlay = MapOverlay.None,
          onMapLoadFinished = { loaded = true },
        )
      }

      waitUntilMap("the style to load") { loaded }
      val session = cameraState.map as GlJsMapSession
      waitUntilMap("the map to draw a frame") { session.hasLoadedFirstStyle }
      waitForIdle()
      assertFalse(
        session.lastDrawnOverdrawInspector,
        "the inspector starts off, so a later true is the toggle taking effect",
      )

      options = MapOptions(renderOptions = RenderOptions(isOverdrawInspectorEnabled = true))
      waitForIdle()
      repeat(4) {
        yieldToBrowser()
        waitForIdle()
      }

      assertTrue(
        session.lastDrawnOverdrawInspector,
        "the next Compose draw should run with the inspector on; a camera move should not be " +
          "what applies the flag",
      )
    }
}
