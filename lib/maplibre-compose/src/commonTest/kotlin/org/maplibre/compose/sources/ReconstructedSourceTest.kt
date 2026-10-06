package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
  fun a_type_without_a_public_class_is_kept() {
    val clip = buildJsonObject {
      put("type", "video")
      put("attribution", "© nobody")
    }
    val video = assertIs<UnmodeledRasterSource>(reconstructedSource("clip", clip))
    assertEquals("© nobody", video.attributionHtml)
    val canvas = buildJsonObject { put("type", "canvas") }
    assertIs<UnmodeledRasterSource>(reconstructedSource("paint", canvas))
    val future = buildJsonObject { put("type", "future") }
    assertIs<UnmodeledSource>(reconstructedSource("next", future))
    assertIs<UnmodeledSource>(reconstructedSource("blank", buildJsonObject {}))
  }
}
