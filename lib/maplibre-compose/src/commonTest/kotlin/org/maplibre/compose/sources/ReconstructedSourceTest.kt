package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReconstructedSourceTest {

  @Test
  fun reconstructed_json_round_trips_without_inventing_constructor_defaults() {
    val definition = buildJsonObject {
      put("type", "vector")
      put("url", "https://example.invalid/tiles.json")
      put("attribution", "© nobody")
    }
    val source = assertIs<VectorTileSource>(reconstructedSource("tiles", definition))
    assertEquals(definition, source.toJson())
    assertEquals("© nobody", source.attributionHtml)
  }

  @Test
  fun a_type_without_a_class_is_not_reconstructed() {
    assertNull(reconstructedSource("clip", buildJsonObject { put("type", "video") }))
    assertNull(reconstructedSource("blank", buildJsonObject {}))
  }
}
