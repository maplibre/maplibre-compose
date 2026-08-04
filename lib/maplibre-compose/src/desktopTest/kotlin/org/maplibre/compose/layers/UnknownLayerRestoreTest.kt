package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesktopStyle
import org.maplibre.compose.util.toJsonElement

/**
 * Takes a base-style layer out of a loaded style and puts it back, which is what an
 * [Anchor.Replace] does when its last replacement leaves.
 *
 * The layer that comes back is rebuilt from JSON MapLibre reported rather than from anything the
 * composition holds, so every key it fails to replay is lost for the rest of that style's life —
 * and lost quietly. A filter that does not come back draws the features the style excluded; a
 * `source-layer` that does not come back selects nothing out of a vector source and draws an empty
 * layer. Neither raises an error, so only reading the live layer afterwards catches it.
 */
class UnknownLayerRestoreTest {

  @Test
  fun `a reconstructed layer emits back every key MapLibre reported`() {
    val definition = Json.parseToJsonElement(REPORTED_LINE_LAYER).jsonObject

    // Equality against the definition itself, rather than a list of keys to check: a key added to
    // [REPORTED_LINE_LAYER] is then covered whether or not anyone remembers to assert on it.
    assertEquals(definition, UnknownLayer("roads", definition).toJson())
  }

  @Test
  fun `restoring a replaced base layer keeps its filter and source-layer`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? DesktopStyle, "Errors: ${it.errors}")

      val original = assertNotNull(style.getLayer(ROADS))
      // Read through the live map rather than the style JSON, and read it before the replacement,
      // so what is asserted afterwards is what MapLibre itself had rather than what the test
      // believes it wrote.
      val binding = original.binding
      val filterBefore =
        assertNotNull(binding.withMap { map -> map.layerFilter(ROADS)?.toJsonElement() })
      assertEquals("transportation", binding.withMap { map -> map.layerSourceLayer(ROADS) })

      // The exact sequence LayerManager runs: the replacement goes in above the layer it replaces,
      // the original comes out, and later the original goes back below the replacement.
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
  fun `a restored base layer is bound to the style it went back into`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? DesktopStyle, "Errors: ${it.errors}")

      val original = assertNotNull(style.getLayer(ROADS))
      val binding = original.binding
      style.removeLayer(original)
      style.addLayer(original)

      // The descriptor is written pre-attach and emitted as one object on add; this is the other
      // half of the split, where a setter has to reach the live layer instead. A restored layer
      // that kept a stale binding would take the write silently and leave the map unchanged.
      original.minZoom = 7f

      assertEquals(7.0, binding.withMap { map -> map.layerMinZoom(ROADS) })
    }
  }

  private companion object {
    const val ROADS = "roads"

    /**
     * A layer object in the shape `styleLayerJson` reports one, keys and number forms included.
     *
     * Taken from what MapLibre returned for the `roads` layer of [VECTOR_STYLE] rather than written
     * by hand, so the JSON test and the live one are looking at the same thing. Note what is
     * missing: the style declares `metadata` on that layer and MapLibre does not report it back, so
     * there is none to restore.
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
     * A vector source and a filtered layer over it, which is the combination that breaks.
     *
     * The tiles are never fetched: the layer's `minzoom` is above the zoom the map settles on, so
     * MapLibre asks the source for nothing, and the test needs the layer's definition rather than
     * its contents. The unreachable host is deliberate — a test that reaches the network is worse
     * than no test.
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
