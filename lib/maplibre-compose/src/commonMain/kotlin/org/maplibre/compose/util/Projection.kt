package org.maplibre.compose.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan
import org.maplibre.spatialk.geojson.Position

/** MapLibre projects with 512px tiles, not the more common 256. */
private const val TILE_SIZE = 512.0

private const val EARTH_CIRCUMFERENCE_METERS = 2.0 * PI * 6378137.0

/**
 * Latitude beyond which Web Mercator is undefined; mbgl's `util::LATITUDE_MAX` to full precision.
 */
private const val MERCATOR_MAX_LATITUDE = 85.051128779806604

/** Zoom bounds mbgl clamps to before projecting; `util::MIN_ZOOM` and `util::MAX_ZOOM`. */
private const val MIN_PROJECTION_ZOOM = 0.0

private const val MAX_PROJECTION_ZOOM = 25.5

/**
 * Meters per logical pixel at [latitude] and [zoom]. Transcribed from
 * `mbgl::Projection::getMetersPerPixelAtLatitude`, clamps included.
 */
internal fun metersPerDpAtLatitude(zoom: Double, latitude: Double): Double {
  val clampedZoom = zoom.coerceIn(MIN_PROJECTION_ZOOM, MAX_PROJECTION_ZOOM)
  val clampedLatitude = latitude.coerceIn(-MERCATOR_MAX_LATITUDE, MERCATOR_MAX_LATITUDE)
  return cos(clampedLatitude * PI / 180.0) * EARTH_CIRCUMFERENCE_METERS /
    (2.0.pow(clampedZoom) * TILE_SIZE)
}

/**
 * The Web Mercator distance in logical pixels between [from] and [to] at [zoom], along the shorter
 * way around the world. Transcribed from `mbgl::Projection::project` with the unwrap that
 * `Transform::flyTo` applies to its start point.
 */
internal fun mercatorPixelDistance(zoom: Double, from: Position, to: Position): Double {
  val worldSize = 2.0.pow(zoom.coerceIn(MIN_PROJECTION_ZOOM, MAX_PROJECTION_ZOOM)) * TILE_SIZE
  var deltaLongitude = (to.longitude - from.longitude) % 360.0
  if (deltaLongitude > 180.0) deltaLongitude -= 360.0
  if (deltaLongitude < -180.0) deltaLongitude += 360.0
  val deltaX = worldSize * deltaLongitude / 360.0
  val deltaY = worldSize * (mercatorY(from.latitude) - mercatorY(to.latitude))
  return hypot(deltaX, deltaY)
}

/** The Web Mercator y of [latitude] as a fraction of the world, from 0 at the north edge. */
private fun mercatorY(latitude: Double): Double {
  val clamped = latitude.coerceIn(-MERCATOR_MAX_LATITUDE, MERCATOR_MAX_LATITUDE)
  return 0.5 - ln(tan(PI / 4.0 + clamped * PI / 360.0)) / (2.0 * PI)
}
