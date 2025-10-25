package org.maplibre.compose.util

import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

public data class PositionQuad(
  val topLeft: Position,
  val topRight: Position,
  val bottomRight: Position,
  val bottomLeft: Position,
) {
  public fun toGeoJson(): Polygon =
    Polygon(listOf(listOf(topRight, topLeft, bottomLeft, bottomRight, topRight)))
}
