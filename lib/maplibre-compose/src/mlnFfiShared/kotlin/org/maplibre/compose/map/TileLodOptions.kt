package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * MapLibre Native tile cover when the camera is pitched.
 *
 * @param mode Which tile-cover algorithm to use.
 * @param minRadius Radius, in tile units, around the view point that always uses the finest zoom.
 *   Must be greater than 1. Ignored when [mode] is [TileLodMode.Distance].
 * @param scale Scale on camera distance. Values greater than 1 coarsen tiles farther from the
 *   camera.
 * @param pitchThreshold Camera pitch in degrees from nadir, matching
 *   [org.maplibre.compose.camera.CameraPosition.tilt], above which level-of-detail reduction runs.
 *   0 always reduces; 180 never reduces.
 * @param zoomShift Added to the zoom used for level-of-detail. `-1` cuts the tile count by about
 *   four. Ignored when [mode] is [TileLodMode.Distance].
 */
@Immutable
public actual data class TileLodOptions(
  val mode: TileLodMode = TileLodMode.Default,
  val minRadius: Double = 3.0,
  val scale: Double = 1.0,
  val pitchThreshold: Double = 60.0,
  val zoomShift: Double = 0.0,
) {
  public actual companion object Companion {
    public actual val Standard: TileLodOptions = TileLodOptions()

    public actual val Performance: TileLodOptions =
      TileLodOptions(minRadius = 2.0, scale = 1.5, pitchThreshold = 45.0, zoomShift = -1.0)

    public actual val HighDetail: TileLodOptions =
      TileLodOptions(minRadius = 5.0, pitchThreshold = 85.0)
  }
}
