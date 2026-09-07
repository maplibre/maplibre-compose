package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleLayerTypesTest {
  @Test
  fun layer_types_report_every_layer_in_style_order(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(LAYERED_STYLE)
      val style = assertNotNull(fixture.style)

      val types = style.layerTypes()

      // Native's own annotations layer is omitted, as getLayer omits it.
      assertEquals(mapOf("backdrop" to "background", "lakes" to "fill"), types)
      assertEquals(listOf("backdrop", "lakes"), types.keys.toList())
      assertEquals("fill", style.layerType("lakes"))
      assertNull(style.layerType("missing"))
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
              "type": "geojson",
              "data": { "type": "FeatureCollection", "features": [] }
            }
          },
          "layers": [
            { "id": "backdrop", "type": "background" },
            { "id": "lakes", "type": "fill", "source": "water" }
          ]
        }
        """
          .trimIndent()
      )
  }
}
