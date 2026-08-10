package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

/**
 * The rest of the corpus loads styles inline, so this is the only place the browser fetches one.
 * Desktop's equivalent is `DesktopPackagedStyleTest`.
 */
class BrowserStyleUriTest {

  @Test
  fun a_style_loads_from_a_url(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Uri(STYLE.toDataUrl()))
      val style = assertNotNull(fixture.style)

      assertEquals(listOf("fetched"), style.getSources().map { it.id })
      assertEquals(listOf("bg"), style.getLayers().map { it.id })
    }
  }

  private companion object {
    /** A URL the map has to fetch, without a server to fetch it from. */
    fun String.toDataUrl(): String =
      "data:application/json;charset=utf-8," + (js("encodeURIComponent")(this) as String)

    val STYLE =
      """
      {
        "version": 8,
        "sources": {
          "fetched": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } }
        },
        "layers": [{ "id": "bg", "type": "background", "paint": { "background-color": "#000000" } }]
      }
      """
        .trimIndent()
  }
}
