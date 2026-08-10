package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.util.toJsonElement

/**
 * The restored layer is rebuilt from JSON MapLibre reported, and any key it fails to replay is lost
 * without an error, so the assertions read the live layer.
 */
class UnknownLayerRestoreTest {

  @Test
  fun restoring_a_replaced_base_layer_keeps_its_filter_and_source_layer() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")

      val original = assertNotNull(style.getLayer(ROADS))
      val binding = assertIs<MlnFfiStyleBinding>(original.binding)
      val filterBefore =
        assertNotNull(binding.readMap { map -> map.layerFilter(ROADS)?.toJsonElement() })
      assertEquals("transportation", binding.readMap { map -> map.layerSourceLayer(ROADS) })

      val replacement = BackgroundLayer("user-replacement")
      // The exact sequence LayerManager runs.
      style.addLayerAbove(ROADS, replacement)
      style.removeLayer(original)
      assertNull(style.getLayer(ROADS), "The replaced layer should be out of the style")
      style.addLayerBelow(replacement.id, original)

      assertEquals(filterBefore, binding.readMap { map -> map.layerFilter(ROADS)?.toJsonElement() })
      assertEquals("transportation", binding.readMap { map -> map.layerSourceLayer(ROADS) })
      assertEquals("vec", binding.readMap { map -> map.layerSourceId(ROADS) })
      assertEquals(14.0, binding.readMap { map -> map.layerMinZoom(ROADS) })
    }
  }

  @Test
  fun a_restored_base_layer_is_bound_to_the_style_it_went_back_into() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")

      val original = assertNotNull(style.getLayer(ROADS))
      val binding = assertIs<MlnFfiStyleBinding>(original.binding)
      style.removeLayer(original)
      style.addLayer(original)

      // A restored layer that kept a stale binding would take this write silently.
      original.minZoom = 7f

      assertEquals(7.0, binding.readMap { map -> map.layerMinZoom(ROADS) })
    }
  }

  private companion object {
    const val ROADS = "roads"

    /**
     * The tile host is unresolvable on purpose, and the layer's `minzoom` keeps MapLibre from
     * requesting tiles at all.
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
