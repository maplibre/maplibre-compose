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
   * @param xSkew How much to skew the projection on the x-axis.
   * @param ySkew How much to skew the projection on the y-axis.
   */
  public data class Axonometric(
    public val xSkew: Double = 0.0,
    public val ySkew: Double = 1.0,
  ) : CameraProjection
}
