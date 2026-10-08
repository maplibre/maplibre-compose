package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

class GeoJsonSourceStyleReloadTest {
  @Test
  fun a_source_handle_expires_after_a_real_base_style_reload(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val source =
        GeoJsonSource(
          id = "points",
          data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>(emptyList())),
        )
      val handle = assertIs<MutableGeoJsonSourceHandle>(fixture.state.style.sources.add(source))

      fixture.loadStyle(ReplacementStyle)

      assertNull(handle.asMutable)
      // The expired handle ignores the write instead of reaching the replacement style.
      handle.setData(GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>(emptyList())))
      assertNull(fixture.state.style.sources["points"])
    }
  }

  private companion object {
    val ReplacementStyle =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"background","type":"background"}]}"""
      )
  }
}
