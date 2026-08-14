package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RasterDemSourceTest {

  @Test
  fun tms_tiles_are_rejected() {
    assertFailsWith<IllegalArgumentException> {
      RasterDemSource(
        id = "dem",
        tiles = listOf("https://example.invalid/{z}/{x}/{y}.png"),
        options = TileSetOptions(tileCoordinateSystem = TileCoordinateSystem.TMS),
      )
    }
  }

  @Test
  fun xyz_tiles_omit_scheme() {
    val json =
      RasterDemSource(
          id = "dem",
          tiles = listOf("https://example.invalid/{z}/{x}/{y}.png"),
          options = TileSetOptions(tileCoordinateSystem = TileCoordinateSystem.XYZ),
        )
        .toJson()
    assertFalse("scheme" in json)
  }
}
