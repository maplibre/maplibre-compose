package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.maplibre.compose.gljs.SourceHandle
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

/** MapLibre GL JS has no `scheme` on a raster-dem source, and reads the custom encoding. */
class BrowserRasterDemSourceTest {

  @Test
  fun tms_tiles_are_refused_on_attach(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val source =
        RasterDemSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(tileCoordinateSystem = TileCoordinateSystem.TMS),
        )

      val error = assertFailsWith<IllegalStateException> { style.addSource(source) }
      assertContains(error.message.orEmpty(), "TileCoordinateSystem.XYZ")
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
    }
  }

  @Test
  fun xyz_tiles_attach_with_their_custom_encoding(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val source =
        RasterDemSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(tileCoordinateSystem = TileCoordinateSystem.XYZ),
          demEncoding = RasterDemEncoding.Custom(redFactor = 2f),
        )

      style.addSource(source)

      // MapLibre GL JS keeps the encoding on the live source, which is what the map renders with.
      assertEquals("custom", source.liveEncoding())
      assertEquals(2.0, source.liveRedFactor())
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
    }
  }

  /** The DEM encoding that MapLibre GL JS holds for this live source. */
  private fun RasterDemSource.liveEncoding(): String? =
    glJsBinding
      ?.withMap { map -> map.getSource<SourceHandle>(id)?.asDynamic()?.encoding }
      ?.unsafeCast<String?>()

  /** The custom encoding's red factor that MapLibre GL JS holds for this live source. */
  private fun RasterDemSource.liveRedFactor(): Double? =
    glJsBinding
      ?.withMap { map -> map.getSource<SourceHandle>(id)?.asDynamic()?.redFactor }
      ?.unsafeCast<Double?>()

  private companion object {
    /** Unresolvable on purpose: tests must not reach the network. */
    const val TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.png"
  }
}
