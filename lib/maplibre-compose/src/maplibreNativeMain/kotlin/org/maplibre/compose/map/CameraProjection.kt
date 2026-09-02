package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/** The camera projection that MapLibre Native uses to render the map. */
@Immutable
public sealed interface CameraProjection {
  /** Renders the map with perspective projection. */
  public data object Perspective : CameraProjection

  /**
   * Renders the map with axonometric projection.
   *
   * MapLibre Native does not apply camera padding to the axonometric projection matrix. With
   * asymmetric padding, the camera target renders at the geometric viewport center, while some
   * camera operations still calculate from the padded center. See
   * [MapLibre Native issue #4545](https://github.com/maplibre/maplibre-native/issues/4545).
   *
   * @param xSkew How much to skew the projection on the x-axis.
   * @param ySkew How much to skew the projection on the y-axis.
   */
  public data class Axonometric(
    public val xSkew: Double = 0.0,
    public val ySkew: Double = 1.0,
  ) : CameraProjection
}
