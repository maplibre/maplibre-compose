package org.maplibre.compose.util

import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * The four geographic corners of an image placed on the map.
 *
 * Positions are matched to the non-repeated world; wrap longitudes into ±180° before placing an
 * image near the antimeridian.
 */
public data class PositionQuad(
  val topLeft: Position,
  val topRight: Position,
  val bottomRight: Position,
  val bottomLeft: Position,
) {
  public fun toGeoJson(): Polygon =
    Polygon(listOf(listOf(topRight, topLeft, bottomLeft, bottomRight, topRight)))
}
