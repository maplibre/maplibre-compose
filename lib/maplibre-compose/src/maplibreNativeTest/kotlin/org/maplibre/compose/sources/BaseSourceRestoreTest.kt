package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.install
import org.maplibre.compose.style.readMap
import org.maplibre.compose.style.uninstall

class BaseSourceRestoreTest {

  /**
   * MapLibre retains a tiled source's templates, so a reconstructed source can be added to a later
   * style.
   */
  @Test
  fun re_adding_a_base_style_source_keeps_its_tiles() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VectorStyle))
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      val source = assertIs<VectorTileSource>(style.readMap { style.getSource(SourceId) })
      runBlocking { style.uninstall(source) }
      runBlocking { style.install(source) }

      val restored = assertIs<VectorTileSource>(style.readMap { style.getSource(SourceId) })
      assertEquals(JsonPrimitive("vector"), restored.toJson()["type"])
      assertEquals(
        listOf("https://example.invalid/{z}/{x}/{y}.pbf"),
        (restored.toJson()["tiles"] as? JsonArray)?.map { (it as JsonPrimitive).content },
      )
      assertEquals(Attribution, restored.attributionHtml)
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  @Test
  fun every_source_in_the_base_style_is_reported() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VectorStyle))
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      assertEquals(
        mapOf(SourceId to "vector", RasterSourceId to "raster"),
        checkNotNull(style.readMap { style.sourceIds().mapNotNull(style::getSource) }).associate {
          source ->
          source.id to (source.toJson()["type"] as? JsonPrimitive)?.content
        },
      )
    }
  }

  private companion object {
    const val SourceId = "vec"
    const val RasterSourceId = "sat"

    const val Attribution = "&copy; Nobody"

    /**
     * No layer draws from either source: MapLibre will not remove a source a layer still draws
     * from. The hosts do not resolve, so no tile is ever requested.
     */
    val VectorStyle =
      """
      {
        "version": 8,
        "name": "base-source-restore-test",
        "sources": {
          "vec": {
            "type": "vector",
            "tiles": ["https://example.invalid/{z}/{x}/{y}.pbf"],
            "minzoom": 0,
            "maxzoom": 14,
            "attribution": "$Attribution"
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
