package org.maplibre.compose.map

import kotlin.test.Test
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class MapTileLodTest {

  @Test
  fun every_preset_can_be_applied_to_a_loaded_map(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(TILED_STYLE)
      fixture.session.setTileLodSettings(TileLodOptions.Performance)
      fixture.session.setTileLodSettings(TileLodOptions.HighDetail)
      fixture.session.setTileLodSettings(TileLodOptions.Standard)
      fixture.settle()
    }
  }

  private companion object {
    val TILED_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {
            "tiles": {
              "type": "raster",
              "tiles": ["https://example.invalid/{z}/{x}/{y}.png"],
              "tileSize": 256
            }
          },
          "layers": [{"id": "tiles", "type": "raster", "source": "tiles"}]
        }
        """
          .trimIndent()
      )
  }
}
