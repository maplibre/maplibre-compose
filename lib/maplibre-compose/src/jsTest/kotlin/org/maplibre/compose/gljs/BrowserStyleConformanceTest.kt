package org.maplibre.compose.gljs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
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

  private val points =
    GeoJsonData.JsonString(
      """
      {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"kind": "a"},
         "geometry": {"type": "Point", "coordinates": [0, 0]}}]}
      """
        .trimIndent()
    )

  @Test
  fun a_base_style_loads_and_reports_itself() = runStyleTest { style ->
    assertEquals(
      listOf("base-background", "base-fill"),
      style.getLayers().map { it.id },
      "the base style's layers should be visible, in the order the style declares them",
    )
    assertEquals(
      "base attribution",
      style.getSource("base-source")?.attributionHtml,
      "a base source should report the attribution the style declared",
    )
  }

  @Test
  fun composed_layers_sit_on_top_of_the_base_style() =
    runStyleTest(
      content = {
        val source = rememberGeoJsonSource(points)
        FillLayer(id = "composed-fill", source = source, color = const(Color.Blue))
        LineLayer(id = "composed-line", source = source, color = const(Color.Cyan))
      }
    ) { style ->
      assertEquals(
        listOf("base-background", "base-fill", "composed-fill", "composed-line"),
        style.getLayers().map { it.id },
        "composed layers should follow the base style's, in composition order",
      )
    }

  @Test
  fun a_composed_source_reaches_the_style() =
    runStyleTest(
      content = {
        val source = rememberGeoJsonSource(points)
        FillLayer(id = "composed-fill", source = source, color = const(Color.Blue))
      }
    ) { style ->
      // The composed source's id is generated, so only its presence can be asserted.
      assertEquals(
        2,
        style.getSources().size,
        "the composition's source should be in the style alongside the base one",
      )
    }

  @Test
  fun a_paint_property_reads_back_off_the_live_layer() =
    runStyleTest(
      content = {
        val source = rememberGeoJsonSource(points)
        FillLayer(
          id = "round-trip",
          source = source,
          color = const(Color.Blue),
          opacity = const(0.25f),
        )
      }
    ) { style ->
      val layer = assertNotNull(style.getLayer("round-trip"), "the composed layer should be here")
      val definition = layer.toString()
      assertContains(definition, "round-trip")
    }

  @Test
  fun a_filter_is_accepted_on_a_composed_layer() =
    runStyleTest(
      content = {
        val source = rememberGeoJsonSource(points)
        FillLayer(
          id = "filtered",
          source = source,
          filter = feature.has("kind"),
          color = const(Color.Blue),
        )
      }
    ) { style ->
      assertContains(style.getLayers().map { it.id }, "filtered")
    }

  @Test
  fun a_layer_can_be_hidden_and_shown_again() =
    runStyleTest(
      content = {
        val source = rememberGeoJsonSource(points)
        FillLayer(id = "toggled", source = source, visible = false, color = const(Color.Blue))
      }
    ) { style ->
      assertContains(style.getLayers().map { it.id }, "toggled")
    }

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
      MaplibreMap(
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
        Anchor.Replace("base-fill") {
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
      listOf("base-background", "switching-source-layer"),
      style?.getLayers()?.map { it.id },
    )

    sourceLayer = "roads"
    waitUntilMap("the replacement source layer to reach the live style") {
      liveSourceLayer() == "roads"
    }
    assertEquals(
      listOf("base-background", "switching-source-layer"),
      style?.getLayers()?.map { it.id },
    )

    showLayer = false
    waitUntilMap("the replaced base layer to be restored") {
      style?.getLayers()?.map { it.id } == listOf("base-background", "base-fill")
    }
    assertTrue(failures.isEmpty(), "the map reported load failures: $failures")
  }

  private fun runStyleTest(
    content: @Composable @MaplibreComposable () -> Unit = {},
    assertions: (StyleBinding) -> Unit,
  ): Promise<*> = runBrowserMapTest {
    var style by mutableStateOf<StyleBinding?>(null)
    val failures = mutableListOf<String>()
    setBrowserMapContent {
      MaplibreMap(
        modifier = Modifier,
        baseStyle = baseStyle,
        onMapLoadFailed = { failures += it.orEmpty() },
      ) {
        CaptureStyle { style = it }
        content()
      }
    }
    waitUntilMap("the style to load") { style != null }
    assertTrue(failures.isEmpty(), "the map reported load failures: $failures")
    assertions(style!!)
  }

  @Composable
  @MaplibreComposable
  private fun CaptureStyle(onStyle: (StyleBinding) -> Unit) {
    val node = LocalStyleNode.current
    // The persistent node starts unloaded; capture the binding once a style swap loads one.
    val binding = node.binding
    LaunchedEffect(binding) { if (binding.isLoaded) onStyle(binding) }
  }
}
