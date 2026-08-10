package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Rebuilding a base-style layer from the JSON MapLibre reported, which is what an [Anchor.Replace]
 * does when its last replacement leaves. A key that fails to replay is lost without an error.
 */
class UnknownLayerJsonTest {

  @Test
  fun a_reconstructed_layer_emits_back_every_key_maplibre_reported() {
    val definition = Json.parseToJsonElement(REPORTED_LINE_LAYER).jsonObject

    assertEquals(definition, UnknownLayer("roads", definition).toJson())
  }

  private companion object {
    /**
     * What MapLibre actually returned for a filtered line layer, keys and number forms included.
     * Note the missing `metadata`: MapLibre does not report it back.
     */
    val REPORTED_LINE_LAYER =
      """
      {
        "id": "roads",
        "type": "line",
        "source": "vec",
        "source-layer": "transportation",
        "minzoom": 14.0,
        "filter": ["==", ["get", "class"], "motorway"],
        "layout": { "line-cap": "round" },
        "paint": { "line-color": ["rgba", 255.0, 0.0, 0.0, 1.0] }
      }
      """
        .trimIndent()
  }
}
