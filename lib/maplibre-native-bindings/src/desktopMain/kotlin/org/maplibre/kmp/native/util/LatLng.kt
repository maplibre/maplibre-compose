package org.maplibre.kmp.native.util

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public data class LatLng
@CalledByNative
public constructor(
  @get:CalledByNative val latitude: Double,
  @get:CalledByNative val longitude: Double,
) {
  init {
    require(!latitude.isNaN()) { "latitude must not be NaN" }
    require(!longitude.isNaN()) { "longitude must not be NaN" }
    require(latitude in -90.0..90.0) { "latitude must be between -90 and 90" }
    require(longitude.isFinite()) { "longitude must not be infinite" }
  }
}
