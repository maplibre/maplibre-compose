package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.toDataJson
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class StyleDefinitionAndIdentityTest {
  @Test
  fun a_definition_changed_before_install_reaches_the_loaded_style() {
    val binding = RecordingStyleBinding()
    val source = GeoJsonSource("updated", GeoJsonData.JsonString("{}"), GeoJsonOptions())
    val replacement = GeoJsonData.Features(featureCollectionOf())
    source.setDesiredData(replacement)

    SourceInstallation(binding, source.definition())

    assertEquals(replacement.toDataJson(), binding.sources.getValue("updated")["data"])
  }

  @Test
  fun a_geojson_update_submits_immutable_data_once_per_changed_definition() = runTest {
    val binding = RecordingStyleBinding()
    val source =
      GeoJsonSource("updated", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val installation = SourceInstallation(binding, source.definition())
    val replacement = GeoJsonData.Uri("https://example.com/data.geojson")
    source.setDesiredData(replacement)

    installation.update(source.definition())
    installation.update(source.definition())

    assertEquals<List<GeoJsonData>?>(listOf(replacement), binding.installedGeoJson["updated"])
  }

  @Test
  fun a_handle_for_an_invalidated_identity_fails_clearly() = runTest {
    val definition = SourceDefinition.Json("stale", buildJsonObject { put("type", "vector") })
    val style = RecordingStyleBinding()
    val handle = SourceInstallation(style, definition)
    val identity = style.identity

    style.invalidate()

    val error = assertFailsWith<IllegalStateException> { handle.update(definition) }
    assertTrue(error.message.orEmpty().contains("stale loaded-style identity"))
    assertSame(identity, style.identity)
    assertEquals(setOf("stale"), style.sources.keys)
  }
}
