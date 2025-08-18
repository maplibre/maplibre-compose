package org.maplibre.kmp.native.camera

import org.maplibre.kmp.native.util.EdgeInsets
import org.maplibre.kmp.native.util.LatLng
import org.maplibre.kmp.native.util.ScreenCoordinate
import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

/**
 * Camera position and orientation options.
 *
 * Note: `center` and `anchor` are mutually exclusive in MapLibre Native. Use the companion object
 * factory methods to create instances correctly.
 */
@ExposeToNative
public data class CameraOptions
@CalledByNative
public constructor(
  @get:CalledByNative val center: LatLng? = null,
  @get:CalledByNative val padding: EdgeInsets? = null,
  @get:CalledByNative val anchor: ScreenCoordinate? = null,
  @get:CalledByNative val zoom: Double? = null,
  @get:CalledByNative val bearing: Double? = null,
  @get:CalledByNative val pitch: Double? = null,
) {
  public companion object {
    /**
     * Creates a camera options with a geographic center point. Use this for absolute positioning of
     * the camera.
     */
    @JvmStatic
    public fun centered(
      center: LatLng,
      zoom: Double? = null,
      bearing: Double? = null,
      pitch: Double? = null,
      padding: EdgeInsets? = null,
    ): CameraOptions =
      CameraOptions(
        center = center,
        padding = padding,
        anchor = null, // Explicitly null when using center
        zoom = zoom,
        bearing = bearing,
        pitch = pitch,
      )

    /**
     * Creates a camera options with a screen anchor point. Use this for transformations that should
     * keep a specific screen point fixed.
     */
    @JvmStatic
    public fun anchored(
      anchor: ScreenCoordinate,
      zoom: Double? = null,
      bearing: Double? = null,
      pitch: Double? = null,
      padding: EdgeInsets? = null,
    ): CameraOptions =
      CameraOptions(
        center = null, // Explicitly null when using anchor
        padding = padding,
        anchor = anchor,
        zoom = zoom,
        bearing = bearing,
        pitch = pitch,
      )
  }
}
