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

      assertNotNull(fixture.style)
        .addSource(
          GeoJsonSource(
            id = "late-source",
            data = GeoJsonData.Features(featureCollectionOf()),
            options = GeoJsonOptions(),
          )
        )

      val expectedSourceId =
        when (mapLibreFlavor) {
          MapLibreFlavor.GL_JS -> "late-source"
          MapLibreFlavor.NATIVE -> null
        }
      fixture.pumpUntil("the source change to be reported") {
        expectedSourceId in fixture.sourceChanges
      }
    }
  }
}
