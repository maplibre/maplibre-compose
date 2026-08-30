package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import org.maplibre.compose.layers.HillshadeLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.install
import org.maplibre.compose.style.uninstall
import org.maplibre.compose.util.onMap
import org.maplibre.nativeffi.style.SourceType
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** MapLibre reports a tiled source's type, templates, and attribution. */
class TiledSourceAttachTest {

  @Test
  fun a_raster_source_carries_a_raster_layer() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      val fromTiles =
        RasterSource(
          id = "tiles",
          tiles = listOf(TILE_TEMPLATE),
          options =
            TileSetOptions(
              minZoom = 2,
              maxZoom = 12,
              tileCoordinateSystem = TileCoordinateSystem.TMS,
              boundingBox = BOUNDS,
              attributionHtml = ATTRIBUTION,
            ),
          tileSize = 512,
        )
      val fromUrl = RasterSource(id = "url", uri = TILEJSON_URL, tileSize = 256)
      style.install(fromUrl)

      val layer = RasterLayer("raster", fromTiles)
      style.install(fromTiles)
      style.install(layer)

      style.onMap { map ->
        assertEquals(SourceType.RASTER, map.styleSourceType("tiles"))
        assertEquals(SourceType.RASTER, map.styleSourceType("url"))
        assertEquals(ATTRIBUTION, map.styleSourceInfo("tiles")?.attribution)
        assertEquals(listOf(TILE_TEMPLATE), map.styleSourceInfo("tiles")?.tileJson?.tileUrls)
        assertEquals("tiles", map.layerSourceId("raster"))
      }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  @Test
  fun a_raster_dem_source_carries_a_hillshade_layer() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      val fromTiles =
        RasterDemSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(maxZoom = 12, attributionHtml = ATTRIBUTION),
          tileSize = 512,
          demEncoding = RasterDemEncoding.Terrarium,
        )
      val fromUrl = RasterDemSource(id = "dem-url", uri = TILEJSON_URL, tileSize = 256)
      style.install(fromUrl)

      val layer = HillshadeLayer("hillshade", fromTiles)
      style.install(fromTiles)
      style.install(layer)

      style.onMap { map ->
        // A hillshade layer over a plain RASTER source draws nothing, with no error to explain it.
        assertEquals(SourceType.RASTER_DEM, map.styleSourceType("dem"))
        assertEquals(SourceType.RASTER_DEM, map.styleSourceType("dem-url"))
        assertEquals(ATTRIBUTION, map.styleSourceInfo("dem")?.attribution)
        assertEquals("dem", map.layerSourceId("hillshade"))
      }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  @Test
  fun a_vector_source_reaches_the_style_from_either_constructor() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      val fromTiles =
        VectorSource(
          id = "tiles",
          tiles = listOf(VECTOR_TILE_TEMPLATE),
          options = TileSetOptions(minZoom = 4, maxZoom = 14, attributionHtml = ATTRIBUTION),
        )
      val fromUrl = VectorSource(id = "url", uri = TILEJSON_URL)
      style.install(fromTiles)
      style.install(fromUrl)

      style.onMap { map ->
        assertEquals(SourceType.VECTOR, map.styleSourceType("tiles"))
        assertEquals(SourceType.VECTOR, map.styleSourceType("url"))
        assertEquals(ATTRIBUTION, map.styleSourceInfo("tiles")?.attribution)
      }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  @Test
  fun a_removed_tiled_source_can_be_added_to_a_later_style() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      val witness = RasterSource(id = "witness", tiles = listOf(TILE_TEMPLATE), tileSize = 256)
      style.install(witness)

      val source =
        RasterSource(
          id = "tiles",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(attributionHtml = ATTRIBUTION),
          tileSize = 256,
        )
      style.install(source)
      style.uninstall(source)
      // Read through the loaded style because the reusable definition contains no live map.
      assertEquals(
        false,
        style.onMap { map -> map.styleSourceExists("tiles") },
        "the source should be out of the style",
      )

      style.install(source)
      style.onMap { map ->
        assertEquals(SourceType.RASTER, map.styleSourceType("tiles"))
        assertEquals(ATTRIBUTION, map.styleSourceInfo("tiles")?.attribution)
      }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  /**
   * MapLibre Native implements only `mapbox` and `terrarium`, so the binding takes the downgraded
   * form; RasterDemSourceJsonTest asserts the downgrade itself.
   */
  @Test
  fun a_custom_dem_encoding_still_reaches_the_style() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      val source =
        RasterDemSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(),
          tileSize = 256,
          demEncoding = RasterDemEncoding.Custom(redFactor = 2f),
        )
      // The definition keeps the encoding it was given; the downgrade happens on installation.
      assertEquals(Json.parseToJsonElement("\"custom\""), source.toJson()["encoding"])

      val layer = HillshadeLayer("hillshade", source)
      style.install(source)
      style.install(layer)

      style.onMap { map -> assertEquals(SourceType.RASTER_DEM, map.styleSourceType("dem")) }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  private companion object {
    /** Unresolvable on purpose: tests must not reach the network. */
    const val TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.png"
    const val VECTOR_TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.pbf"
    const val TILEJSON_URL = "https://example.invalid/tiles.json"

    const val ATTRIBUTION = "&copy; Nobody"

    val BOUNDS = BoundingBox(southwest = Position(-10.0, -20.0), northeast = Position(30.0, 40.0))
  }
}
