package org.maplibre.compose.editing

import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Returns a copy of this geometry with every position replaced by [transform]. Structure, ring
 * closure and foreign members are preserved; bbox is null.
 */
@Suppress("UNCHECKED_CAST")
public fun <G : Geometry> G.mapPositions(transform: (Position) -> Position): G {
  val mapped: Geometry =
    when (this) {
      is Point -> Point(transform(coordinates), foreignMembers = foreignMembers)
      is MultiPoint -> MultiPoint(coordinates.map(transform), foreignMembers = foreignMembers)
      is LineString -> LineString(coordinates.map(transform), foreignMembers = foreignMembers)
      is MultiLineString ->
        MultiLineString(
          coordinates.map { part -> part.map(transform) },
          foreignMembers = foreignMembers,
        )
      is Polygon ->
        Polygon(
          coordinates.map { ring -> mapRing(ring, transform) },
          foreignMembers = foreignMembers,
        )
      is MultiPolygon ->
        MultiPolygon(
          coordinates.map { part -> part.map { ring -> mapRing(ring, transform) } },
          foreignMembers = foreignMembers,
        )
      is GeometryCollection<*> ->
        GeometryCollection(
          geometries.map { it.mapPositions(transform) },
          foreignMembers = foreignMembers,
        )
    }
  return mapped as G
}

// The closing position is the transformed first position, so a ring stays closed when transform
// is not a function of position identity.
private fun mapRing(ring: List<Position>, transform: (Position) -> Position): List<Position> {
  if (ring.size < 2 || ring.first() != ring.last()) return ring.map(transform)
  val open = ring.subList(0, ring.size - 1).map(transform)
  return open.plusElement(open.first())
}

/**
 * Whether [position] lies inside this box. Longitudes are compared modulo 360, so a box past 180
 * contains positions wrapped to [-180, 180].
 */
public operator fun BoundingBox.contains(position: Position): Boolean {
  if (position.latitude < south || position.latitude > north) return false
  var width = east - west
  if (width < 0) width += 360.0
  if (width >= 360.0) return true
  var offset = (position.longitude - west) % 360.0
  if (offset < 0) offset += 360.0
  return offset <= width
}
