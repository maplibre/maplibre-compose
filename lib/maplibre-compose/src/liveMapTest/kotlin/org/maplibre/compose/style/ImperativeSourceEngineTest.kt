package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.map.FakeMapAdapter
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

/** Pins that both engines refuse the same imperative source removals the same way. */
class ImperativeSourceEngineTest {

  @Test
  fun removing_an_in_use_source_throws_on_this_engine(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(SOURCE_IN_USE_STYLE)
      val style = assertNotNull(fixture.style)

      assertFailsWith<StyleMutationException> { style.removeSource("src") }
      assertTrue(style.sourceExists("src") == true, "the refused removal keeps the source")

      style.removeLayer("lyr")
      style.removeSource("src")
      assertTrue(style.sourceExists("src") == false)
    }
  }

  @Test
  fun removing_a_missing_id_through_the_state_throws_on_this_engine(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val state = MapState()
      try {
        val adapter = FakeMapAdapter()
        state.attachSession(adapter)
        state.callbacks.onStyleChanged(adapter, fixture.style)

        assertFailsWith<IllegalArgumentException> { state.sources.remove("absent") }

        state.sources.add(appSource("app-src"))
        assertNotNull(state.sources["app-src"])
        state.sources.remove("app-src")
        assertNull(state.sources["app-src"])
      } finally {
        state.close()
      }
    }
  }

  private companion object {
    fun appSource(id: String) =
      GeoJsonSource(id, GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())

    val SOURCE_IN_USE_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {
            "src": {
              "type": "geojson",
              "data": { "type": "FeatureCollection", "features": [] }
            }
          },
          "layers": [{ "id": "lyr", "type": "fill", "source": "src" }]
        }
        """
          .trimIndent()
      )
  }
}
