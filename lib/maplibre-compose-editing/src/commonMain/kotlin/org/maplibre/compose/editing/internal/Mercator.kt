package org.maplibre.compose.editing.internal

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.SensitiveGeoJsonApi

// Web Mercator in world units: x and y span [0, 1] across the world, longitude 0 at x = 0.5.

internal const val MAX_MERCATOR_LATITUDE: Double = 85.051129

internal fun mercatorX(longitude: Double): Double = longitude / 360.0 + 0.5

internal fun mercatorY(latitude: Double): Double {
  val lat = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
  val s = sin(lat * PI / 180.0)
  return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}

internal fun longitudeFromMercatorX(x: Double): Double = (x - 0.5) * 360.0

internal fun latitudeFromMercatorY(y: Double): Double =
  (2 * atan(exp((0.5 - y) * 2 * PI)) - PI / 2) * 180.0 / PI

/** Wraps a longitude into [-180, 180]. */
internal fun wrapLongitude(longitude: Double): Double {
  if (longitude >= -180.0 && longitude <= 180.0) return longitude
  var wrapped = (longitude + 180.0) % 360.0
  if (wrapped < 0) wrapped += 360.0
  return wrapped - 180.0
}

/** Shortest Mercator x difference modulo the world width. */
internal fun wrapMercatorDx(dx: Double): Double {
  var wrapped = (dx + 0.5) % 1.0
  if (wrapped < 0) wrapped += 1.0
  return wrapped - 0.5
}

/** World-unit distance between two positions with the shorter way round the globe. */
internal fun mercatorDistance(a: Position, b: Position): Double {
  val dx = wrapMercatorDx(mercatorX(b.longitude) - mercatorX(a.longitude))
  val dy = mercatorY(b.latitude) - mercatorY(a.latitude)
  return sqrt(dx * dx + dy * dy)
}

/** Midpoint of the straight segment as rendered: mean longitude, mean Mercator y. */
internal fun mercatorMidpoint(a: Position, b: Position): Position =
  Position(
    longitude = (a.longitude + b.longitude) / 2,
    latitude = latitudeFromMercatorY((mercatorY(a.latitude) + mercatorY(b.latitude)) / 2),
  )

/** Moves this position by ([dx], [dy]) world units. Altitude and further values are kept. */
internal fun Position.translatedInMercator(dx: Double, dy: Double): Position =
  withLonLat(
    longitude = longitudeFromMercatorX(mercatorX(longitude) + dx),
    latitude = latitudeFromMercatorY(mercatorY(latitude) + dy),
  )

/** Copies this position with new horizontal coordinates, keeping altitude and further values. */
@OptIn(SensitiveGeoJsonApi::class)
internal fun Position.withLonLat(longitude: Double, latitude: Double): Position {
  if (size <= 2) return Position(longitude, latitude)
  val extra = toList()
  return when (extra.size) {
    3 -> Position(longitude, latitude, extra[2])
    else -> Position(longitude, latitude, extra[2], *extra.subList(3, extra.size).toDoubleArray())
  }
}

/**
 * Returns [position] with the horizontal coordinates of [target] and the further coordinate values
 * of [position] when [target] has only longitude and latitude.
 */
internal fun mergedPosition(position: Position, target: Position): Position =
  if (target.size > 2 || position.size <= 2) target
  else position.withLonLat(target.longitude, target.latitude)

/** World-unit distance from [p] to the segment [a]-[b], with the segment on its nearest copy. */
internal fun mercatorSegmentDistance(
  px: Double,
  py: Double,
  a: Position,
  b: Position,
): Double {
  val ax = mercatorX(a.longitude)
  val ay = mercatorY(a.latitude)
  val bx = mercatorX(b.longitude)
  val by = mercatorY(b.latitude)
  val shift = round(px - (ax + bx) / 2)
  var best = pointSegmentDistance(px, py, ax + shift, ay, bx + shift, by)
  // A segment wider than half the world can be nearer on a neighbouring copy.
  if (kotlin.math.abs(bx - ax) > 0.5) {
    best = minOf(best, pointSegmentDistance(px, py, ax + shift - 1, ay, bx + shift - 1, by))
    best = minOf(best, pointSegmentDistance(px, py, ax + shift + 1, ay, bx + shift + 1, by))
  }
  return best
}

private fun pointSegmentDistance(
  px: Double,
  py: Double,
  ax: Double,
  ay: Double,
  bx: Double,
  by: Double,
): Double {
  val vx = bx - ax
  val vy = by - ay
  val lengthSquared = vx * vx + vy * vy
  val t =
    if (lengthSquared == 0.0) 0.0
    else (((px - ax) * vx + (py - ay) * vy) / lengthSquared).coerceIn(0.0, 1.0)
  val dx = px - (ax + t * vx)
  val dy = py - (ay + t * vy)
  return sqrt(dx * dx + dy * dy)
}
