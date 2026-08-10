package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class BaseStyleSourceReadTest {

  @Test
  fun a_source_reports_the_attribution_its_style_declares(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(ATTRIBUTED_STYLE)
      val source = assertNotNull(assertNotNull(fixture.style).getSource("attributed"))

      assertEquals("inline attribution", source.attributionHtml)
    }
  }

  @Test
  fun a_style_reports_only_the_sources_it_declares(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(ATTRIBUTED_STYLE)
      val style = assertNotNull(fixture.style)

      assertEquals(listOf("attributed"), style.getSources().map { it.id })
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
