package org.maplibre.compose.util

import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/** Every position in the geometry, in document order. */
internal fun Geometry.positions(): Sequence<Position> =
  when (this) {
    is Point -> sequenceOf(coordinates)
    is MultiPoint -> coordinates.asSequence()
    is LineString -> coordinates.asSequence()
    is MultiLineString -> coordinates.asSequence().flatten()
    is Polygon -> coordinates.asSequence().flatten()
    is MultiPolygon -> coordinates.asSequence().flatten().flatten()
    is GeometryCollection<*> -> geometries.asSequence().flatMap { it.positions() }
  }
