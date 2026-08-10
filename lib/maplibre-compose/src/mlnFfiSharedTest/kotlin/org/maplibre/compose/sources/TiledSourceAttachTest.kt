package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import org.maplibre.compose.layers.HillshadeLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.compose.util.onMap
import org.maplibre.nativeffi.style.SourceType
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * The tiled sources, and the layer types that only exist to draw one.
 *
 * MapLibre reports back only a source's type, volatility, and attribution, so the assertions are
 * those plus the fact that it accepted the JSON at all. No tiles are fetched: a source's definition
 * is parsed when it is added, long before any tile is requested.
 */
class TiledSourceAttachTest {

  @Test
  fun a_raster_source_carries_a_raster_layer() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")

      // Every TileSetOptions field at once, none at its default.
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
      style.addSource(fromUrl)

      val layer = RasterLayer("raster", fromTiles)
      // The layer must attach a fresh source before MapLibre validates the layer JSON.
      style.addLayer(layer)

      layer.onMap { map ->
        assertEquals(SourceType.RASTER, map.styleSourceType("tiles"))
        assertEquals(SourceType.RASTER, map.styleSourceType("url"))
        // The one TileSetOptions field MapLibre reports back.
        assertEquals(ATTRIBUTION, map.styleSourceInfo("tiles")?.attribution)
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
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")

      val fromTiles =
        RasterDemSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(maxZoom = 12, attributionHtml = ATTRIBUTION),
          tileSize = 512,
          demEncoding = RasterDemEncoding.Terrarium,
        )
      val fromUrl = RasterDemSource(id = "dem-url", uri = TILEJSON_URL, tileSize = 256)
      style.addSource(fromUrl)

      val layer = HillshadeLayer("hillshade", fromTiles)
      // The layer must attach a fresh source before MapLibre validates the layer JSON.
      style.addLayer(layer)

      layer.onMap { map ->
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
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")

      val fromTiles =
        VectorSource(
          id = "tiles",
          tiles = listOf(VECTOR_TILE_TEMPLATE),
          options = TileSetOptions(minZoom = 4, maxZoom = 14, attributionHtml = ATTRIBUTION),
        )
      val fromUrl = VectorSource(id = "url", uri = TILEJSON_URL)
      style.addSource(fromTiles)
      style.addSource(fromUrl)

      fromTiles.onMap { map ->
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
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")

      // Stays in the style for the length of the test, so there is always a live binding to read
      // the map through.
      val witness = RasterSource(id = "witness", tiles = listOf(TILE_TEMPLATE), tileSize = 256)
      style.addSource(witness)

      // A style change detaches sources and re-adds them, so a descriptor must not consume its
      // definition on the way in.
      val source =
        RasterSource(
          id = "tiles",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(attributionHtml = ATTRIBUTION),
          tileSize = 256,
        )
      style.addSource(source)
      style.removeSource(source)
      // Read through the witness: a detached descriptor has no binding, so it answers null either
      // way.
      assertEquals(
        false,
        witness.onMap { map -> map.styleSourceExists("tiles") },
        "the source should be out of the style",
      )

      style.addSource(source)
      source.onMap { map ->
        assertEquals(SourceType.RASTER, map.styleSourceType("tiles"))
        assertEquals(ATTRIBUTION, map.styleSourceInfo("tiles")?.attribution)
      }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  /**
   * MapLibre Native understands only `mapbox` and `terrarium` and refuses the source outright on
   * anything else — where MapLibre GL JS implements the custom encoding, so this is one of the few
   * source assertions that is not shared. See
   * [maplibre-native#2783](https://github.com/maplibre/maplibre-native/issues/2783).
   */
  @Test
  fun a_custom_dem_encoding_falls_back_to_mapbox() {
    val source =
      RasterDemSource(
        id = "dem",
        tiles = listOf(TILE_TEMPLATE),
        options = TileSetOptions(),
        tileSize = 256,
        demEncoding = RasterDemEncoding.Custom(redFactor = 2f),
      )

    assertEquals(Json.parseToJsonElement("\"mapbox\""), source.toJson()["encoding"])
  }

  private companion object {
    /** Unresolvable on purpose: a test that reaches the network is worse than no test. */
    const val TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.png"
    const val VECTOR_TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.pbf"
    const val TILEJSON_URL = "https://example.invalid/tiles.json"

    const val ATTRIBUTION = "&copy; Nobody"

    val BOUNDS = BoundingBox(southwest = Position(-10.0, -20.0), northeast = Position(30.0, 40.0))
  }
}
