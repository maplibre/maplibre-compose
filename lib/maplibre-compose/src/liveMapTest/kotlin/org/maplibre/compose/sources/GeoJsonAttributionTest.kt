package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class GeoJsonAttributionTest {

  @Test
  fun the_engine_keeps_the_attribution_of_a_geojson_source(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(EmptyStyle)
      val source =
        GeoJsonSource(
          id = "attributed",
          data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
        ) {
          attributionHtml = Attribution
        }

      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources.add(source))
      assertEquals(Attribution, handle.attributionHtml)

      // The engine's copy is what a later read of the source's metadata publishes.
      val engineSource = assertNotNull(assertNotNull(fixture.style).getSource("attributed"))
      assertEquals(Attribution, engineSource.attributionHtml)
    }
  }

  private companion object {
    const val Attribution = """<a href="https://example.com">Example data</a>"""
    val EmptyStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
  }
}
