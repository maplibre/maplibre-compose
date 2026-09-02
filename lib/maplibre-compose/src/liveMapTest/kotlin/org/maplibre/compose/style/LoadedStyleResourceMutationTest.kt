package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class LoadedStyleResourceMutationTest {
  @Test
  fun public_commands_mutate_a_live_source_and_style_image(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(EMPTY_STYLE)
      val source =
        GeoJsonSource(
          id = "imperative",
          data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
          options = GeoJsonOptions(),
        )

      assertIs<GeoJsonSourceHandle>(fixture.state.style.sources.add(source))
      assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["imperative"])
      fixture.state.style.images.add("imperative", ImageBitmap(1, 1))
      fixture.settle()

      assertTrue(fixture.state.style.images.remove("imperative"))
      assertTrue(fixture.state.style.sources.remove("imperative"))
      assertNull(fixture.state.style.sources["imperative"])
    }
  }

  private companion object {
    val EMPTY_STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
  }
}
