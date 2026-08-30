package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

class GeoJsonSourceStyleReloadTest {
  @Test
  fun a_source_handle_fails_after_a_real_base_style_reload(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val source =
        GeoJsonSource(
          id = "points",
          data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>(emptyList())),
          options = GeoJsonOptions(),
        )
      checkNotNull(fixture.style).install(source)
      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source("points"))

      fixture.loadStyle(REPLACEMENT_STYLE)

      assertFailsWith<IllegalStateException> {
        handle.setData(GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>(emptyList())))
      }
    }
  }

  private companion object {
    val REPLACEMENT_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"background","type":"background"}]}"""
      )
  }
}
