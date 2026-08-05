package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.nativeffi.geo.CanonicalTileId

/**
 * The tile-to-bounds conversion a computed source answers from. A mistake here is invisible in a
 * rendered map: features are still computed and drawn, just for the wrong ground.
 */
class CanonicalTileBoundsTest {

  @Test
  fun `the single tile at zoom zero covers the Mercator world`() {
    val bounds = CanonicalTileId(z = 0, x = 0, y = 0).toBoundingBox()
    assertEquals(-180.0, bounds.southwest.longitude, TOLERANCE, "west")
    assertEquals(180.0, bounds.northeast.longitude, TOLERANCE, "east")
    assertEquals(-MERCATOR_LIMIT, bounds.southwest.latitude, TOLERANCE, "south")
    assertEquals(MERCATOR_LIMIT, bounds.northeast.latitude, TOLERANCE, "north")
  }

  @Test
  fun `tile row zero is the north of the world rather than the equator`() {
    val northWest = CanonicalTileId(z = 1, x = 0, y = 0).toBoundingBox()
    assertEquals(-180.0, northWest.southwest.longitude, TOLERANCE, "west")
    assertEquals(0.0, northWest.northeast.longitude, TOLERANCE, "east")
    assertEquals(0.0, northWest.southwest.latitude, TOLERANCE, "south")
    assertEquals(MERCATOR_LIMIT, northWest.northeast.latitude, TOLERANCE, "north")

    val southEast = CanonicalTileId(z = 1, x = 1, y = 1).toBoundingBox()
    assertEquals(0.0, southEast.southwest.longitude, TOLERANCE, "west")
    assertEquals(180.0, southEast.northeast.longitude, TOLERANCE, "east")
    assertEquals(-MERCATOR_LIMIT, southEast.southwest.latitude, TOLERANCE, "south")
    assertEquals(0.0, southEast.northeast.latitude, TOLERANCE, "north")
  }

  /** Rows are evenly spaced in Mercator y rather than in latitude. */
  @Test
  fun `a zoom two row spans fewer degrees near the pole than at the equator`() {
    val polar = CanonicalTileId(z = 2, x = 0, y = 0).toBoundingBox()
    val equatorial = CanonicalTileId(z = 2, x = 0, y = 1).toBoundingBox()
    val polarHeight = polar.northeast.latitude - polar.southwest.latitude
    val equatorialHeight = equatorial.northeast.latitude - equatorial.southwest.latitude
    assertEquals(MERCATOR_LIMIT, polar.northeast.latitude, TOLERANCE, "the top of the world")
    assertEquals(0.0, equatorial.southwest.latitude, TOLERANCE, "the equator")
    assertTrue(
      polarHeight < equatorialHeight,
      "expected the $polarHeight degrees of latitude the polar row spans to be under the " +
        "$equatorialHeight the equatorial one does",
    )
  }

  @Test
  fun `neighbouring tiles meet exactly`() {
    val left = CanonicalTileId(z = 3, x = 2, y = 3).toBoundingBox()
    val right = CanonicalTileId(z = 3, x = 3, y = 3).toBoundingBox()
    val below = CanonicalTileId(z = 3, x = 2, y = 4).toBoundingBox()
    assertEquals(left.northeast.longitude, right.southwest.longitude, TOLERANCE, "shared meridian")
    assertEquals(left.southwest.latitude, below.northeast.latitude, TOLERANCE, "shared parallel")
  }

  private companion object {
    /** Latitude beyond which Web Mercator is undefined, which is where tile row zero ends. */
    const val MERCATOR_LIMIT = 85.0511287798066

    const val TOLERANCE = 1e-9
  }
}
