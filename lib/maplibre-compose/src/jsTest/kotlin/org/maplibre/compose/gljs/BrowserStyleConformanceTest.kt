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
import kotlinx.serialization.json.jsonArray
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
  fun location_indicator_uses_regular_layers_and_cleans_up() = runBrowserMapTest {
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
    waitUntilMap("the dot and accuracy circle") {
      style?.layerIds() == listOf("user-accuracy", "user-shadow", "user-top")
    }
    assertEquals(1, style!!.sourceIds().size)
    val radius = style!!.layerProperty("user-accuracy", "circle-radius")!!.jsonArray
    assertEquals("interpolate", radius[0].jsonPrimitive.content)
    assertEquals("zoom", radius[2].jsonArray[0].jsonPrimitive.content)
    val sourceId = style!!.sourceIds().single()
    val sourceBefore = style!!.getSource(sourceId)!!.toJson()
    accuracy = 40.meters
    bearing = Bearing.North
    waitUntilMap("the bearing image") {
      style?.layerIds() == listOf("user-accuracy", "user-shadow", "user-bearing", "user-top")
    }
    assertEquals(
      40.0,
      style!!.featureState(sourceId, null, "0").getValue("accuracy").jsonPrimitive.double,
    )
    assertEquals(
      sourceBefore,
      style!!.getSource(sourceId)!!.toJson(),
      "Heading and accuracy must not change the GeoJSON source",
    )
    location = Position(12.0, 49.0)
    waitForIdle()
    assertEquals(
      radius,
      style!!.layerProperty("user-accuracy", "circle-radius"),
      "Moving the location must not rewrite the radius expression",
    )
    val previousStyle = style
    baseStyle = BaseStyle.Json("""{"version":8,"name":"reloaded","sources":{},"layers":[]}""")
    waitUntilMap("the indicator to return after a style reload") {
      style !== previousStyle &&
        style?.layerIds() == listOf("user-accuracy", "user-shadow", "user-bearing", "user-top")
    }
    assertEquals(
      40.0,
      style!!
        .featureState(style!!.sourceIds().single(), null, "0")
        .getValue("accuracy")
        .jsonPrimitive
        .double,
    )
    location = null
    waitUntilMap("the indicator and its source to be removed") {
      style?.layerIds()?.isEmpty() == true && style?.sourceIds()?.isEmpty() == true
    }
    assertTrue(failures.isEmpty(), "the map reported load failures: $failures")
  }

  @Test
  fun indicator_images_share_interaction_group_but_shadow_and_accuracy_do_not_handle_clicks() =
    runBrowserMapTest {
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
      waitUntilMap("indicator interaction registrations") { latest?.layers?.size == 4 }
      val layers = checkNotNull(latest).layers.associateBy { it.definition.id }
      val top = layers.getValue("user-top")
      val bearing = layers.getValue("user-bearing")
      assertTrue(top.clickGroup != null)
      assertEquals(top.clickGroup, bearing.clickGroup)
      for (image in listOf(top, bearing)) {
        assertEquals(8.dp, image.hitPadding)
        assertEquals(ClickResult.Pass, image.onClick!!(emptyList()))
        assertEquals(ClickResult.Consume, image.onLongClick!!(emptyList()))
        assertEquals(ClickResult.Consume, image.onDoubleClick!!(emptyList()))
      }
      for (id in listOf("user-shadow", "user-accuracy")) {
        val decoration = layers.getValue(id)
        assertEquals(null, decoration.onClick)
        assertEquals(null, decoration.onLongClick)
        assertEquals(null, decoration.onDoubleClick)
      }
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
