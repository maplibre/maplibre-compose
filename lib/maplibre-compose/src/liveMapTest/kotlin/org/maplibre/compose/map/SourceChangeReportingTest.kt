package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertNotNull
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class SourceChangeReportingTest {

  @Test
  fun a_source_added_after_load_reports_a_change(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.sourceChanges.clear()

      val source =
        GeoJsonSource(
          id = SOURCE_ID,
          data = GeoJsonData.Features(featureCollectionOf()),
          options = GeoJsonOptions(),
        )
      assertNotNull(fixture.style).addSource(source)
      fixture.pumpUntil("the added source to be reported") { SOURCE_ID in fixture.sourceChanges }

      // GL JS reports metadata on sourcedata; native reports add and remove from the binding.
      if (mapLibreFlavor != MapLibreFlavor.NATIVE) return@use
      fixture.sourceChanges.clear()
      assertNotNull(fixture.style).removeSource(source)
      fixture.pumpUntil("the removed source to be reported") { SOURCE_ID in fixture.sourceChanges }
    }
  }

  private companion object {
    const val SOURCE_ID = "late-source"
  }
}
