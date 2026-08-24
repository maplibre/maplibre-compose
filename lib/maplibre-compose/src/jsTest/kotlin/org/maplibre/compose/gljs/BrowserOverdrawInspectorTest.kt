package org.maplibre.compose.gljs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.style.BaseStyle

private const val SIZE = GPU_CANVAS_SIZE
private const val RED = "#ff0000"
private const val BLUE = "#0000ff"

/**
 * Two fills that cover the viewport, so the inspector has something to shade and the test has
 * saturated colours it can watch disappear.
 */
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
  fun toggling_overdraw_inspector_through_compose_shades_the_map_without_a_camera_move() =
    MainScope().promise {
      val gpu = browserGpu()
      val gl = gpu.gl.asDynamic()
      GlJsRenderTarget(gl, SIZE, SIZE, generation = 1).use { target ->
        val compositor =
          object : GlJsCompositor {
            override fun acquire(extent: MapExtent) = GlJsFrameTarget.Composited(target)

            override fun close() = Unit
          }

        runComposeUiTest {
          var options by mutableStateOf(MapOptions())
          var loaded = false
          setContent {
            CompositionLocalProvider(LocalGlJsCompositor provides { compositor }) {
              Box(Modifier.size(SIZE.dp)) {
                MaplibreMap(
                  modifier = Modifier,
                  baseStyle = SPLIT_STYLE,
                  options = options,
                  overlay = MapOverlay.None,
                  onMapLoadFinished = { loaded = true },
                )
              }
            }
          }

          waitUntilMap("the split style to load") { loaded }
          waitUntilMap("the split colours to reach the framebuffer") {
            val colours = histogram(readFramebuffer(gl, target.framebuffer, SIZE, SIZE))
            colours.containsKey(RED) && colours.containsKey(BLUE)
          }

          options = MapOptions(renderOptions = RenderOptions(isOverdrawInspectorEnabled = true))
          waitForIdle()
          repeat(8) {
            yieldToBrowser()
            waitForIdle()
          }

          val after = histogram(readFramebuffer(gl, target.framebuffer, SIZE, SIZE))
          assertFalse(
            after.containsKey(RED) || after.containsKey(BLUE),
            "toggling the inspector should shade the current view; a pan should not be " +
              "required. colours=$after",
          )
          assertTrue(after.isNotEmpty(), "the inspector frame should have written pixels")
        }
      }
    }
}
