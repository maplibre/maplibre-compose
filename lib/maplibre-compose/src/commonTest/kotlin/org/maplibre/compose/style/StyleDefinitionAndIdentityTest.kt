package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.toDataJson
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class StyleDefinitionAndIdentityTest {
  @Test
  fun one_definition_can_be_installed_in_two_loaded_styles() {
    val source =
      GeoJsonSource("shared", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val definition = source.definition()
    val first = RecordingStyleBinding()
    val second = RecordingStyleBinding()

    assertTrue(first.addSource(definition))
    assertTrue(second.addSource(definition))

    assertEquals(first.sources.getValue("shared"), second.sources.getValue("shared"))
    assertNotSame(first.identity, second.identity)
  }

  @Test
  fun a_geojson_definition_owns_a_snapshot_of_mutable_input() {
    val features =
      mutableListOf(Feature<Geometry, JsonObject?>(Point(Position(0.0, 0.0)), properties = null))
    val source =
      GeoJsonSource(
        "snapshot",
        GeoJsonData.Features(FeatureCollection(features)),
        GeoJsonOptions(),
      )
    val definition = assertIs<SourceDefinition.GeoJson>(source.definition())

    features.clear()

    val snapshotFeatures = definition.data.toDataJson().let { it as JsonObject }["features"]
    val changedFeatures =
      source
        .definition()
        .let { it as SourceDefinition.GeoJson }
        .data
        .toDataJson()
        .let { it as JsonObject }["features"]
    assertEquals(1, (snapshotFeatures as JsonArray).size)
    assertEquals(0, (changedFeatures as JsonArray).size)
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

  @Test
  fun one_layer_definition_can_be_installed_in_two_loaded_styles() {
    val definition = BackgroundLayer("shared-layer").definition()
    val first = RecordingStyleBinding()
    val second = RecordingStyleBinding()

    assertTrue(first.addLayer(definition, beforeLayerId = ""))
    assertTrue(second.addLayer(definition, beforeLayerId = ""))

    assertEquals(first.layers.getValue("shared-layer"), second.layers.getValue("shared-layer"))
  }
}
