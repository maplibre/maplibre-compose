package org.maplibre.compose.gljs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.rememberVectorTileSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.rememberStyleComposition
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.meters

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
          rememberVectorTileSource(
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

  @Test
  fun location_indicator_is_source_free_and_cleans_up() = runBrowserMapTest {
    var location by mutableStateOf<Position?>(Position(11.0, 48.0))
    var bearing by mutableStateOf<Bearing?>(null)
    var accuracy by mutableStateOf(20.meters)
    var baseStyle by mutableStateOf<BaseStyle>(BaseStyle.Empty)
    var style by mutableStateOf<StyleBinding?>(null)
    val failures = mutableListOf<String>()
    setBrowserMapContent {
      TestMap(baseStyle = baseStyle, onMapLoadFailed = { failures += it.orEmpty() }) {
        CaptureStyle { style = it }
        LocationIndicatorLayer(
          id = "user",
          location = location,
          bearing = bearing,
          accuracyRadius = accuracy,
        )
      }
    }
    waitUntilMap("the custom indicator") { style?.layerIds() == listOf("user") }
    assertTrue(style!!.sourceIds().isEmpty())
    assertEquals("location-indicator", style!!.getLayer("user")!!.definition().type)
    accuracy = 40.meters
    bearing = Bearing.North
    waitForIdle()
    assertEquals(40.0, style!!.layerProperty("user", "accuracy-radius")!!.jsonPrimitive.double)
    location = Position(12.0, 49.0)
    waitForIdle()
    val previousStyle = style
    baseStyle = BaseStyle.Json("""{"version":8,"name":"reloaded","sources":{},"layers":[]}""")
    waitUntilMap("the indicator to return after a style reload") {
      style !== previousStyle && style?.layerIds() == listOf("user")
    }
    assertEquals(40.0, style!!.layerProperty("user", "accuracy-radius")!!.jsonPrimitive.double)
    location = null
    waitUntilMap("the indicator and its source to be removed") {
      style?.layerIds()?.isEmpty() == true && style?.sourceIds()?.isEmpty() == true
    }
    assertTrue(failures.isEmpty(), "the map reported load failures: $failures")
  }

  @Test
  fun indicator_registers_one_interaction_target() = runBrowserMapTest {
    val style = RecordingStyleBinding()
    var latest: DesiredStyleRevision? = null
    setBrowserMapContent {
      val revision by
        rememberStyleComposition(
          maybeStyle = style,
          content = {
            LocationIndicatorLayer(
              id = "user",
              location = Position(0.0, 0.0),
              bearing = Bearing.North,
              accuracyRadius = 20.meters,
              hitPadding = 8.dp,
              onClick = { ClickResult.Pass },
              onLongClick = { ClickResult.Consume },
              onDoubleClick = { ClickResult.Consume },
            )
          },
        )
      LaunchedEffect(revision) { latest = revision }
    }
    waitUntilMap("indicator interaction registrations") { latest?.layers?.size == 1 }
    val indicator = checkNotNull(latest).layers.single()
    assertEquals("user", indicator.definition.id)
    assertEquals(8.dp, indicator.hitPadding)
    assertEquals(ClickResult.Pass, indicator.onClick!!(emptyList()))
    assertEquals(ClickResult.Consume, indicator.onLongClick!!(emptyList()))
    assertEquals(ClickResult.Consume, indicator.onDoubleClick!!(emptyList()))
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
    val state = rememberMapState(baseStyle = baseStyle, content = content)
    val loadState = state.style.loadState
    LaunchedEffect(loadState) {
      if (loadState is StyleLoadState.Failed) onMapLoadFailed(loadState.reason)
    }
    MaplibreMap(state = state, modifier = modifier)
  }
}
