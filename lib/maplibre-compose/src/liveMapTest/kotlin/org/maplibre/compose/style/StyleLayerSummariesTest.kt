package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleLayerSummariesTest {
  @Test
  fun layer_summaries_report_every_layer_in_style_order(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(LAYERED_STYLE)
      val style = assertNotNull(fixture.style)

      val summaries = style.layerSummaries()

      // Native's own annotations layer is omitted, as getLayer omits it.
      assertEquals(
        mapOf(
          "backdrop" to LayerSummary("background", source = null, sourceLayer = null),
          "lakes" to LayerSummary("fill", source = "water", sourceLayer = "lake"),
        ),
        summaries,
      )
      assertEquals(listOf("backdrop", "lakes"), summaries.keys.toList())
    }
  }

  private companion object {
    val LAYERED_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {
            "water": {
              "type": "vector",
              "tiles": ["https://example.invalid/{z}/{x}/{y}.pbf"]
            }
          },
          "layers": [
            { "id": "backdrop", "type": "background" },
            { "id": "lakes", "type": "fill", "source": "water", "source-layer": "lake" }
          ]
        }
        """
          .trimIndent()
      )
  }
}
