package org.maplibre.compose.util

import androidx.compose.runtime.Immutable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * Axis-aligned geographic bounds of the visible map area.
 *
 * MapLibre repeats the world horizontally, and [VisibleBounds] follows that repetition with
 * continuous longitudes: [east] is always numerically greater than or equal to [west], but either
 * may fall outside ±180°, and a viewport wider than one world spans more than 360° of longitude.
 * [north] is always greater than or equal to [south].
 *
 * This is deliberately not a [BoundingBox]. A GeoJSON bounding box (RFC 7946 §5) keeps longitudes
 * within ±180° and instead encodes an antimeridian crossing as an east longitude *less than* the
 * west longitude, and it cannot express a span wider than the world at all. Use [wrapped] to
 * convert to that representation.
 */
@Immutable
public data class VisibleBounds(
  /** The southwest corner: the smallest longitude and latitude of the bounds. */
  public val southwest: Position,
  /** The northeast corner: the largest longitude and latitude of the bounds. */
  public val northeast: Position,
) {
  init {
    require(northeast.longitude >= southwest.longitude) {
      "east must not be less than west: $southwest to $northeast"
    }
    require(northeast.latitude >= southwest.latitude) {
      "north must not be less than south: $southwest to $northeast"
    }
  }

  /** The western longitude bound; may be less than -180°. */
  public val west: Double
    get() = southwest.longitude

  /** The southern latitude bound. */
  public val south: Double
    get() = southwest.latitude

  /** The eastern longitude bound; may be greater than 180°. */
  public val east: Double
    get() = northeast.longitude

  /** The northern latitude bound. */
  public val north: Double
    get() = northeast.latitude

  /** The longitude width in degrees; more than 360° when more than one world is visible. */
  public val longitudeSpan: Double
    get() = east - west

  /** The latitude height in degrees. */
  public val latitudeSpan: Double
    get() = north - south

  /**
   * Converts to a GeoJSON [BoundingBox] (RFC 7946 §5) with longitudes wrapped into ±180°.
   *
   * When the wrapped bounds cross the antimeridian, the result follows the GeoJSON convention: the
   * east longitude is less than the west longitude. A span of 360° or more wraps onto the whole
   * world, from -180° to 180°.
   */
  public fun wrapped(): BoundingBox {
    if (longitudeSpan >= 360.0) {
      return BoundingBox(
        southwest = Position(longitude = -180.0, latitude = south),
        northeast = Position(longitude = 180.0, latitude = north),
      )
    }
    return BoundingBox(
      southwest = Position(longitude = wrapLongitudeWest(west), latitude = south),
      northeast = Position(longitude = wrapLongitudeEast(east), latitude = north),
    )
  }
}

/** Wraps a longitude into the range [-180, 180), keeping a bound on the -180° meridian at -180°. */
internal fun wrapLongitudeWest(longitude: Double): Double {
  val wrapped = (longitude + 180.0) % 360.0
  return if (wrapped < 0.0) wrapped + 180.0 else wrapped - 180.0
}

/** Wraps a longitude into the range (-180, 180], keeping a bound on the 180° meridian at 180°. */
internal fun wrapLongitudeEast(longitude: Double): Double = -wrapLongitudeWest(-longitude)
