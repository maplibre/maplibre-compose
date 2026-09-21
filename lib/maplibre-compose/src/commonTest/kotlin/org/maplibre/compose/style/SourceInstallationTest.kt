package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class SourceInstallationTest {
  @Test
  fun geojson_data_changes_update_the_source_without_resubmitting_unchanged_data() {
    val binding = RecordingStyleBinding()
    val source =
      GeoJsonSource("updated", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val installation = SourceInstallation(binding, source.definition())
    val replacement = GeoJsonData.Uri("https://example.com/data.geojson")
    val changed = GeoJsonSource("updated", replacement, GeoJsonOptions()).definition()

    installation.update(changed)
    installation.update(changed)

    assertEquals<List<GeoJsonData>?>(listOf(replacement), binding.installedGeoJson["updated"])
  }
}
