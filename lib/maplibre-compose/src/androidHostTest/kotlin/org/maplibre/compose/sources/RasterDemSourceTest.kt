package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertFailsWith

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
}
