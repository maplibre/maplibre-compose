package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.compose.util.toJsonElement

/**
 * Takes a base-style layer out of a loaded style and puts it back, which is what an
 * [Anchor.Replace] does when its last replacement leaves.
 *
 * The restored layer is rebuilt from JSON MapLibre reported, and any key it fails to replay is lost
 * without an error, so the assertions read the live layer.
 */
class UnknownLayerRestoreTest {

  @Test
  fun a_reconstructed_layer_emits_back_every_key_maplibre_reported() {
    val definition = Json.parseToJsonElement(REPORTED_LINE_LAYER).jsonObject

    assertEquals(definition, UnknownLayer("roads", definition).toJson())
  }

  @Test
  fun restoring_a_replaced_base_layer_keeps_its_filter_and_source_layer() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")

      val original = assertNotNull(style.getLayer(ROADS))
      val binding = original.binding
      val filterBefore =
        assertNotNull(binding.withMap { map -> map.layerFilter(ROADS)?.toJsonElement() })
      assertEquals("transportation", binding.withMap { map -> map.layerSourceLayer(ROADS) })

      // The exact sequence LayerManager runs.
      val replacement = BackgroundLayer("user-replacement")
      style.addLayerAbove(ROADS, replacement)
      style.removeLayer(original)
      assertNull(style.getLayer(ROADS), "The replaced layer should be out of the style")
      style.addLayerBelow(replacement.id, original)

      assertEquals(filterBefore, binding.withMap { map -> map.layerFilter(ROADS)?.toJsonElement() })
      assertEquals("transportation", binding.withMap { map -> map.layerSourceLayer(ROADS) })
      assertEquals("vec", binding.withMap { map -> map.layerSourceId(ROADS) })
      assertEquals(14.0, binding.withMap { map -> map.layerMinZoom(ROADS) })
    }
  }

  @Test
  fun a_restored_base_layer_is_bound_to_the_style_it_went_back_into() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")

      val original = assertNotNull(style.getLayer(ROADS))
      val binding = original.binding
      style.removeLayer(original)
      style.addLayer(original)

      // A restored layer that kept a stale binding would take this write silently.
      original.minZoom = 7f

      assertEquals(7.0, binding.withMap { map -> map.layerMinZoom(ROADS) })
    }
  }

  private companion object {
    const val ROADS = "roads"

    /**
     * What MapLibre actually returned for the `roads` layer of [VECTOR_STYLE], keys and number
     * forms included. Note the missing `metadata`: MapLibre does not report it back.
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

    /**
     * A vector source and a filtered layer over it. The host is unresolvable on purpose, and the
     * layer's `minzoom` keeps MapLibre from requesting tiles at all.
     */
    val VECTOR_STYLE =
      """
      {
        "version": 8,
        "name": "unknown-layer-restore-test",
        "sources": {
          "vec": {
            "type": "vector",
            "tiles": ["https://example.invalid/{z}/{x}/{y}.pbf"],
            "minzoom": 0,
            "maxzoom": 14
          }
        },
        "layers": [
          { "id": "bg", "type": "background", "paint": { "background-color": "#ffffff" } },
          {
            "id": "roads",
            "type": "line",
            "source": "vec",
            "source-layer": "transportation",
            "minzoom": 14,
            "metadata": { "test:note": "not reported back" },
            "filter": ["==", ["get", "class"], "motorway"],
            "layout": { "line-cap": "round" },
            "paint": { "line-color": "#ff0000" }
          }
        ]
      }
      """
        .trimIndent()
  }
}
