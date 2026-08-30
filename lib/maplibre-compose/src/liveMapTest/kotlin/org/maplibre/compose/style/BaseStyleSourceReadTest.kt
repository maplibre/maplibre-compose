package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class BaseStyleSourceReadTest {
  @Test
  fun a_base_style_source_is_acquired_by_id_after_the_style_is_ready(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(ATTRIBUTED_STYLE)

      val source = assertIs<GeoJsonSourceHandle>(fixture.state.style.source("attributed"))

      assertEquals("inline attribution", source.attributionHtml)
    }
  }

  private companion object {
    val ATTRIBUTED_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {
            "attributed": {
              "type": "geojson",
              "attribution": "inline attribution",
              "data": { "type": "FeatureCollection", "features": [] }
            }
          },
          "layers": []
        }
        """
          .trimIndent()
      )
  }
}
