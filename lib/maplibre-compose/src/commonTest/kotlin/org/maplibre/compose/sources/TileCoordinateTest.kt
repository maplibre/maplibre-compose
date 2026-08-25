package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TileCoordinateTest {

  @Test
  fun the_single_tile_at_zoom_zero_covers_the_mercator_world() {
    val bounds = TileCoordinate(zoomLevel = 0, x = 0, y = 0).bounds
    assertEquals(-180.0, bounds.southwest.longitude, TOLERANCE, "west")
    assertEquals(180.0, bounds.northeast.longitude, TOLERANCE, "east")
    assertEquals(-MERCATOR_LIMIT, bounds.southwest.latitude, TOLERANCE, "south")
    assertEquals(MERCATOR_LIMIT, bounds.northeast.latitude, TOLERANCE, "north")
  }

  @Test
  fun edge_tiles_have_canonical_bounds() {
    val northWest = TileCoordinate(zoomLevel = 1, x = 0, y = 0).bounds
    assertEquals(-180.0, northWest.southwest.longitude, TOLERANCE, "west")
    assertEquals(0.0, northWest.northeast.longitude, TOLERANCE, "east")
    assertEquals(0.0, northWest.southwest.latitude, TOLERANCE, "south")
    assertEquals(MERCATOR_LIMIT, northWest.northeast.latitude, TOLERANCE, "north")

    val southEast = TileCoordinate(zoomLevel = 1, x = 1, y = 1).bounds
    assertEquals(0.0, southEast.southwest.longitude, TOLERANCE, "west")
    assertEquals(180.0, southEast.northeast.longitude, TOLERANCE, "east")
    assertEquals(-MERCATOR_LIMIT, southEast.southwest.latitude, TOLERANCE, "south")
    assertEquals(0.0, southEast.northeast.latitude, TOLERANCE, "north")
  }

  @Test
  fun neighbouring_tiles_meet_exactly() {
    val left = TileCoordinate(zoomLevel = 3, x = 2, y = 3).bounds
    val right = TileCoordinate(zoomLevel = 3, x = 3, y = 3).bounds
    val below = TileCoordinate(zoomLevel = 3, x = 2, y = 4).bounds
    assertEquals(left.northeast.longitude, right.southwest.longitude, TOLERANCE)
    assertEquals(left.southwest.latitude, below.northeast.latitude, TOLERANCE)
  }

  @Test
  fun coordinates_validate_zoom_and_canonical_ranges() {
    assertFailsWith<IllegalArgumentException> { TileCoordinate(-1, 0, 0) }
    assertFailsWith<IllegalArgumentException> { TileCoordinate(33, 0, 0) }
    assertFailsWith<IllegalArgumentException> { TileCoordinate(1, -1, 0) }
    assertFailsWith<IllegalArgumentException> { TileCoordinate(1, 2, 0) }
    assertFailsWith<IllegalArgumentException> { TileCoordinate(1, 0, -1) }
    assertFailsWith<IllegalArgumentException> { TileCoordinate(1, 0, 2) }

    val last = (1L shl 32) - 1
    assertEquals(last, TileCoordinate(32, last, last).x)
  }

  @Test
  fun zoom_options_validate_the_supported_range_and_order() {
    assertFailsWith<IllegalArgumentException> { CustomGeometrySourceOptions(minZoom = -1) }
    assertFailsWith<IllegalArgumentException> { CustomVectorSourceOptions(maxZoom = 33) }
    assertFailsWith<IllegalArgumentException> {
      CustomVectorSourceOptions(minZoom = 4, maxZoom = 3)
    }
    assertTrue(CustomGeometrySourceOptions(minZoom = 0, maxZoom = 32).minZoom == 0)
  }

  private companion object {
    const val MERCATOR_LIMIT = 85.0511287798066
    const val TOLERANCE = 1e-9
  }
}
