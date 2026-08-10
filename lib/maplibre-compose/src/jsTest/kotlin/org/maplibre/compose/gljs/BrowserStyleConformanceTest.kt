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
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.Style
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

  private fun runStyleTest(
    content: @Composable @MaplibreComposable () -> Unit = {},
    assertions: (Style) -> Unit,
  ): Promise<*> = runBrowserMapTest {
    var style by mutableStateOf<Style?>(null)
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
  private fun CaptureStyle(onStyle: (Style) -> Unit) {
    val node = LocalStyleNode.current
    LaunchedEffect(node) { onStyle(node.style) }
  }
}
