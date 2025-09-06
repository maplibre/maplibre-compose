package org.maplibre.kmp.native.map

import org.maplibre.kmp.native.util.LatLngBounds
import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

/** Options to limit what parts of a map are visible. All fields are optional. */
@ExposeToNative
public data class BoundOptions
@CalledByNative
public constructor(
  /** Sets the latitude and longitude bounds to which the camera center are constrained */
  @get:CalledByNative val bounds: LatLngBounds? = null,
  /** Minimum zoom level allowed */
  @get:CalledByNative val minZoom: Double? = null,
  /** Maximum zoom level allowed */
  @get:CalledByNative val maxZoom: Double? = null,
  /** Minimum pitch allowed in degrees */
  @get:CalledByNative val minPitch: Double? = null,
  /** Maximum pitch allowed in degrees */
  @get:CalledByNative val maxPitch: Double? = null,
)
