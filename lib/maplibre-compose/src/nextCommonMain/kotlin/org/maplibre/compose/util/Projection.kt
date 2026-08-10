package org.maplibre.compose.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/** MapLibre projects with 512px tiles; the meters-per-pixel figure depends on it. */
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
 * Meters per logical pixel at [latitude] and [zoom].
 *
 * Transcribed from `mbgl::Projection::getMetersPerPixelAtLatitude`, clamps included; note the 512px
 * tile size rather than the more common 256. Shared rather than reimplemented per backend because
 * it is a property of Web Mercator, which both of them project in.
 */
internal fun metersPerDpAtLatitude(zoom: Double, latitude: Double): Double {
  val clampedZoom = zoom.coerceIn(MIN_PROJECTION_ZOOM, MAX_PROJECTION_ZOOM)
  val clampedLatitude = latitude.coerceIn(-MERCATOR_MAX_LATITUDE, MERCATOR_MAX_LATITUDE)
  return cos(clampedLatitude * PI / 180.0) * EARTH_CIRCUMFERENCE_METERS /
    (2.0.pow(clampedZoom) * TILE_SIZE)
}
