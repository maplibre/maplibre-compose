package org.maplibre.compose.gljs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.rememberVectorSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.MaplibreComposable

@OptIn(ExperimentalTestApi::class)
class BrowserStyleConformanceTest {

  private val baseStyle =
    BaseStyle.Json(
      """
      {
        "version": 8,
        "name": "test",
        "sources": {
          "base-source": {
            "type": "geojson",
            "attribution": "base attribution",
            "data": {"type": "FeatureCollection", "features": []}
          }
        },
        "layers": [
          {"id": "base-background", "type": "background", "paint": {"background-color": "#ff0000"}},
          {"id": "base-fill", "type": "fill", "source": "base-source",
           "paint": {"fill-color": "#00ff00"}}
        ]
      }
      """
        .trimIndent()
    )

  @Test
  fun changing_a_source_layer_recreates_the_layer_and_keeps_its_anchor() = runBrowserMapTest {
    var sourceLayer by mutableStateOf("places")
    var showLayer by mutableStateOf(true)
    var style by mutableStateOf<StyleBinding?>(null)
    val failures = mutableListOf<String>()

    fun liveSourceLayer(): String? =
      ((style?.getLayer("switching-source-layer") as? UnknownLayer)
          ?.definition
          ?.get("source-layer"))
        ?.jsonPrimitive
        ?.content

    setBrowserMapContent {
      TestMap(
        modifier = Modifier,
        baseStyle = baseStyle,
        onMapLoadFailed = { failures += it.orEmpty() },
      ) {
        CaptureStyle { style = it }
        val source =
          rememberVectorSource(
            tiles = listOf("https://example.invalid/{z}/{x}/{y}.pbf"),
            options = TileSetOptions(minZoom = 24, maxZoom = 24),
          )
        Anchor.Below("base-fill") {
          if (showLayer) {
            FillLayer(
              id = "switching-source-layer",
              source = source,
              sourceLayer = sourceLayer,
              color = const(Color.Blue),
            )
          }
        }
      }
    }

    waitUntilMap("the initial source layer to reach the live style") {
      liveSourceLayer() == "places"
    }
    assertEquals(
      listOf("base-background", "switching-source-layer", "base-fill"),
      style?.layerIds(),
    )

    sourceLayer = "roads"
    waitUntilMap("the recreated source layer to reach the live style") {
      liveSourceLayer() == "roads"
    }
    assertEquals(
      listOf("base-background", "switching-source-layer", "base-fill"),
      style?.layerIds(),
    )

    showLayer = false
    waitUntilMap("the removed layer to leave the live style") {
      style?.layerIds() == listOf("base-background", "base-fill")
    }
    assertTrue(failures.isEmpty(), "the map reported load failures: $failures")
  }

  @Composable
  @MaplibreComposable
  private fun CaptureStyle(onStyle: (StyleBinding) -> Unit) {
    val node = LocalStyleNode.current
    LaunchedEffect(node) { onStyle(node.style) }
  }

  @Composable
  private fun TestMap(
    baseStyle: BaseStyle,
    modifier: Modifier = Modifier,
    onMapLoadFailed: (String?) -> Unit = {},
    content: @Composable @MaplibreComposable () -> Unit = {},
  ) {
    val state = rememberMapState(initialBaseStyle = baseStyle, content = content)
    val loadState = state.style.loadState
    LaunchedEffect(loadState) {
      if (loadState is StyleLoadState.Failed) onMapLoadFailed(loadState.reason)
    }
    MaplibreMap(state = state, modifier = modifier)
  }
}
