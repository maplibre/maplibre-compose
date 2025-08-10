package org.maplibre.kmp.native.map

import org.maplibre.kmp.native.util.Size
import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

/**
 * Configuration options for initializing a Map. This is a simple data holder that specifies various
 * map settings.
 */
@ExposeToNative
public data class MapOptions
@CalledByNative
public constructor(
  @get:CalledByNative val mapMode: MapMode = MapMode.CONTINUOUS,
  @get:CalledByNative val constrainMode: ConstrainMode = ConstrainMode.HEIGHT_ONLY,
  @get:CalledByNative val viewportMode: ViewportMode = ViewportMode.DEFAULT,
  @get:CalledByNative val crossSourceCollisions: Boolean = true,
  @get:CalledByNative val northOrientation: NorthOrientation = NorthOrientation.UPWARDS,
  @get:CalledByNative val size: Size = Size(64, 64),
  @get:CalledByNative val pixelRatio: Float = 1.0f,
) {
  init {
    require(pixelRatio > 0) { "pixelRatio must be positive" }
    require(!size.isEmpty) { "size must not be empty" }
  }
}
