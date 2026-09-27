package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.maplibre.compose.layers.LayerSummary
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleLayerSummariesTest {
  @Test
  fun published_metadata_reports_base_resources_in_style_order(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(LAYERED_STYLE)
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

  private companion object {
    val LAYERED_STYLE =
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
