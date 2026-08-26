package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding

class UnknownSourceRestoreTest {

  @Test
  fun a_base_style_source_is_reconstructed_with_the_style_spec_s_name_for_its_type() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      val source = assertIs<UnknownSource>(style.getSource(SOURCE_ID))

      assertEquals("vector", (source.definition["type"] as? JsonPrimitive)?.content)
      assertEquals(
        listOf("https://example.invalid/{z}/{x}/{y}.pbf"),
        (source.definition["tiles"] as? JsonArray)?.map { (it as JsonPrimitive).content },
      )
      assertEquals(ATTRIBUTION, source.attributionHtml)
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  /**
   * MapLibre retains a tiled source's templates, so a reconstructed source can be added to a later
   * style.
   */
  @Test
  fun re_adding_a_base_style_source_keeps_its_tiles() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      val source = assertIs<UnknownSource>(style.getSource(SOURCE_ID))
      style.removeSource(source)
      style.addSource(source)

      val restored = assertIs<UnknownSource>(style.getSource(SOURCE_ID))
      assertEquals(
        listOf("https://example.invalid/{z}/{x}/{y}.pbf"),
        (restored.definition["tiles"] as? JsonArray)?.map { (it as JsonPrimitive).content },
      )
      assertEquals(ATTRIBUTION, restored.attributionHtml)
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  @Test
  fun every_source_in_the_base_style_is_reported() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      assertEquals(
        mapOf(SOURCE_ID to "vector", RASTER_SOURCE_ID to "raster"),
        style.getSources().associate { source ->
          source.id to
            (assertIs<UnknownSource>(source).definition["type"] as? JsonPrimitive)?.content
        },
      )
      // MapLibre's own, in every map whether or not anything draws an annotation.
      assertNull(style.getSource(ANNOTATION_SOURCE_ID))
    }
  }

  private companion object {
    const val SOURCE_ID = "vec"
    const val RASTER_SOURCE_ID = "sat"

    const val ANNOTATION_SOURCE_ID = "org.maplibre.annotations"
    const val ATTRIBUTION = "&copy; Nobody"

    /**
     * No layer draws from either source: MapLibre will not remove a source a layer still draws
     * from. The hosts do not resolve, so no tile is ever requested.
     */
    val VECTOR_STYLE =
      """
      {
        "version": 8,
        "name": "unknown-source-restore-test",
        "sources": {
          "vec": {
            "type": "vector",
            "tiles": ["https://example.invalid/{z}/{x}/{y}.pbf"],
            "minzoom": 0,
            "maxzoom": 14,
            "attribution": "$ATTRIBUTION"
          },
          "sat": {
            "type": "raster",
            "tiles": ["https://example.invalid/{z}/{x}/{y}.png"],
            "tileSize": 256
          }
        },
        "layers": [
          { "id": "bg", "type": "background", "paint": { "background-color": "#ffffff" } }
        ]
      }
      """
        .trimIndent()
  }
}
