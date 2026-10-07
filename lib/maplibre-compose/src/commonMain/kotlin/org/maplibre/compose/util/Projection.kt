package org.maplibre.compose.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** MapLibre projects with 512px tiles, not the more common 256. */
private const val TileSize = 512.0

private const val EarthCircumferenceMeters = 2.0 * PI * 6378137.0

/**
 * Latitude beyond which Web Mercator is undefined; mbgl's `util::LATITUDE_MAX` to full precision.
 */
private const val MercatorMaxLatitude = 85.051128779806604

/** Zoom bounds mbgl clamps to before projecting; `util::MIN_ZOOM` and `util::MAX_ZOOM`. */
private const val MinProjectionZoom = 0.0

private const val MaxProjectionZoom = 25.5

/**
 * Meters per logical pixel at [latitude] and [zoom]. Transcribed from
 * `mbgl::Projection::getMetersPerPixelAtLatitude`, clamps included.
 */
internal fun metersPerDpAtLatitude(zoom: Double, latitude: Double): Double {
  val clampedZoom = zoom.coerceIn(MinProjectionZoom, MaxProjectionZoom)
  val clampedLatitude = latitude.coerceIn(-MercatorMaxLatitude, MercatorMaxLatitude)
  return cos(clampedLatitude * PI / 180.0) * EarthCircumferenceMeters /
    (2.0.pow(clampedZoom) * TileSize)
}

/** The Web Mercator y of [latitude] as a fraction of the world, from 0 at the north edge. */
internal fun mercatorY(latitude: Double): Double {
  val clamped = latitude.coerceIn(-MercatorMaxLatitude, MercatorMaxLatitude)
  return 0.5 - ln(tan(PI / 4.0 + clamped * PI / 360.0)) / (2.0 * PI)
}
