package org.maplibre.kmp.native.util

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public data class LatLngBounds
@CalledByNative
public constructor(
  @get:CalledByNative val north: Double,
  @get:CalledByNative val east: Double,
  @get:CalledByNative val south: Double,
  @get:CalledByNative val west: Double,
) {
  init {
    require(north.isFinite()) { "north latitude must be finite" }
    require(east.isFinite()) { "east longitude must be finite" }
    require(south.isFinite()) { "south latitude must be finite" }
    require(west.isFinite()) { "west longitude must be finite" }
  }

  /** Get the northeast corner of this bounds. */
  public val northeast: LatLng
    get() = LatLng(north, east)

  /** Get the southwest corner of this bounds. */
  public val southwest: LatLng
    get() = LatLng(south, west)

  /** Get the northwest corner of this bounds. */
  public val northwest: LatLng
    get() = LatLng(north, west)

  /** Get the southeast corner of this bounds. */
  public val southeast: LatLng
    get() = LatLng(south, east)

  /** Get the center point of this bounds. */
  public val center: LatLng
    get() = LatLng((south + north) / 2.0, (west + east) / 2.0)

  /** Returns true if these bounds are valid. */
  public fun isValid(): Boolean = south <= north && west <= east

  /** Returns true if these bounds are empty (have zero area). */
  public fun isEmpty(): Boolean = south > north || west > east

  /** Returns true if the bounds span the antimeridian. */
  public fun crossesAntimeridian(): Boolean {
    // For unwrapped bounds, this checks if west > east after wrapping
    val wrappedWest = if (west < -180.0) west + 360.0 else if (west > 180.0) west - 360.0 else west
    val wrappedEast = if (east < -180.0) east + 360.0 else if (east > 180.0) east - 360.0 else east
    return wrappedWest > wrappedEast
  }

  /** Returns true if this bounds contains the given point. */
  public fun contains(latLng: LatLng): Boolean {
    val lat = latLng.latitude
    val lng = latLng.longitude

    return lat in south..north && lng >= west && lng <= east
  }

  /** Returns true if this bounds contains the given bounds. */
  public fun contains(bounds: LatLngBounds): Boolean {
    return bounds.south >= south &&
      bounds.north <= north &&
      bounds.west >= west &&
      bounds.east <= east
  }

  /** Returns true if this bounds intersects with the given bounds. */
  public fun intersects(bounds: LatLngBounds): Boolean {
    return !(bounds.south > north ||
      bounds.north < south ||
      bounds.west > east ||
      bounds.east < west)
  }

  /** Returns a new bounds extended to include the given point. */
  public fun extend(latLng: LatLng): LatLngBounds {
    return LatLngBounds(
      north = maxOf(north, latLng.latitude),
      east = maxOf(east, latLng.longitude),
      south = minOf(south, latLng.latitude),
      west = minOf(west, latLng.longitude),
    )
  }

  /** Returns a new bounds extended to include the given bounds. */
  public fun extend(bounds: LatLngBounds): LatLngBounds {
    return LatLngBounds(
      north = maxOf(north, bounds.north),
      east = maxOf(east, bounds.east),
      south = minOf(south, bounds.south),
      west = minOf(west, bounds.west),
    )
  }

  public companion object {
    /** Return bounds covering the entire (unwrapped) world. */
    public fun world(): LatLngBounds = LatLngBounds(90.0, 180.0, -90.0, -180.0)

    /** Return bounds consisting of a single point. */
    public fun singleton(latLng: LatLng): LatLngBounds =
      LatLngBounds(latLng.latitude, latLng.longitude, latLng.latitude, latLng.longitude)

    /** Return the convex hull of two points; the smallest bounds that contains both. */
    public fun hull(a: LatLng, b: LatLng): LatLngBounds =
      LatLngBounds(
        north = maxOf(a.latitude, b.latitude),
        east = maxOf(a.longitude, b.longitude),
        south = minOf(a.latitude, b.latitude),
        west = minOf(a.longitude, b.longitude),
      )

    /**
     * Return bounds that may serve as the identity element for the extend operation. This
     * represents empty bounds where south > north and west > east.
     */
    public fun empty(): LatLngBounds =
      LatLngBounds(north = -90.0, east = -180.0, south = 90.0, west = 180.0)
  }
}
