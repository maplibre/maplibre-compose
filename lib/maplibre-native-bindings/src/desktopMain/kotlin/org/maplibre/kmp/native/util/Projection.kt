package org.maplibre.kmp.native.util

import smjni.jnigen.ExposeToNative

/** Spherical Mercator projection utilities. */
@ExposeToNative
public object Projection {

  /**
   * Returns the number of meters per pixel at the given latitude and zoom level.
   *
   * @param lat Latitude in degrees
   * @param zoom Zoom level
   * @return Meters per pixel at the specified location and zoom
   */
  @JvmStatic public external fun getMetersPerPixelAtLatitude(lat: Double, zoom: Double): Double

  // TODO: Add worldSize(scale: Double): Double

  // TODO: Add projectedMetersForLatLng(latLng: LatLng): ProjectedMeters

  // TODO: Add latLngForProjectedMeters(projectedMeters: ProjectedMeters): LatLng

  // TODO: Add project(latLng: LatLng, scale: Double): Point

  // TODO: Add project(latLng: LatLng, zoom: Int): Point

  // TODO: Add unproject(point: Point, scale: Double): LatLng
}
