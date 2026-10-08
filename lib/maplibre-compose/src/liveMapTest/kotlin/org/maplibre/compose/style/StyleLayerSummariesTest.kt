package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.maplibre.compose.layers.LayerSummary
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleLayerSummariesTest {
  @Test
  fun published_metadata_reports_base_resources_in_style_order(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(LayeredStyle)
      val style = assertNotNull(fixture.style)

      val summaries = style.baseLayers

      assertEquals(
        listOf(
          LayerSummary("backdrop", "background", source = null, sourceLayer = null),
          LayerSummary("lakes", "fill", source = "water", sourceLayer = "lake"),
          LayerSummary("rivers", "line", source = "water", sourceLayer = "river"),
          LayerSummary("pins", "circle", source = "points", sourceLayer = null),
        ),
        summaries,
      )
      assertEquals(setOf("points", "water"), style.baseSources.keys)
      assertEquals("water", assertNotNull(style.baseSources["water"]).id)
    }
  }

  @Test
  fun external_edits_refresh_resources_in_engine_order_and_keep_unchanged_handles(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(LayeredStyle)
        val binding = assertNotNull(fixture.style)
        val unchanged = assertNotNull(fixture.state.style.sources["points"])
        val layer = assertNotNull(fixture.state.style.layers["pins"])
        binding.awaitOwner {
          binding.addSource(
            GeoJsonSource(
                "external",
                GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
                GeoJsonOptions.Standard,
              )
              .definition()
          )
          binding.addLayer(TestLayer("external", "background").definition(), "pins")
          binding.moveLayer("pins", "backdrop")
        }
        fixture.state.styleAuthority.refreshStyleResources(fixture.session)
        assertEquals(
          binding.awaitOwner { binding.sourceIds() },
          fixture.state.style.sources.map { it.id },
        )
        assertEquals(
          listOf("pins", "backdrop", "lakes", "rivers", "external"),
          fixture.state.style.layers.map { it.id },
        )
        assertSame(unchanged, fixture.state.style.sources["points"])
        assertSame(layer, fixture.state.style.layers["pins"])
        assertNotNull(fixture.state.style.sources["external"])
      }
    }

  private companion object {
    val LayeredStyle =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {
            "points": {
              "type": "geojson",
              "data": { "type": "FeatureCollection", "features": [] }
            },
            "water": {
              "type": "vector",
              "tiles": ["https://example.invalid/{z}/{x}/{y}.pbf"]
            }
          },
          "layers": [
            { "id": "backdrop", "type": "background" },
            { "id": "lakes", "type": "fill", "source": "water", "source-layer": "lake" },
            { "id": "rivers", "type": "line", "source": "water", "source-layer": "river" },
            { "id": "pins", "type": "circle", "source": "points" }
          ]
        }
        """
          .trimIndent()
      )
  }
}
