package org.maplibre.compose.map

/**
 * How MapLibre Native picks a zoom for each tile when the camera is pitched.
 *
 * [Default] keeps the finest tiles at the centre of the screen. [Distance] keeps them nearest the
 * camera, so the foreground is denser than the horizon.
 */
public enum class TileLodMode {
  /**
   * Finest tiles at the screen centre, then coarser tiles outside [TileLodOptions.minRadius].
   * [TileLodOptions.scale], [TileLodOptions.pitchThreshold], and [TileLodOptions.zoomShift] all
   * apply.
   */
  Default,

  /**
   * Finest tiles nearest the camera. [TileLodOptions.scale] and [TileLodOptions.pitchThreshold]
   * apply; [TileLodOptions.minRadius] and [TileLodOptions.zoomShift] do not.
   */
  Distance,
}
