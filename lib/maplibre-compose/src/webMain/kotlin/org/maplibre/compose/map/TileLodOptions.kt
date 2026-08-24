package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * MapLibre GL JS tile cover when the camera is pitched. These parameters have no effect at pitch 0,
 * and the largest effect when the horizon is on screen.
 *
 * @param maxZoomLevelsOnScreen Cap on distinct zooms when the horizon is on screen. Increasing it
 *   makes zoom decay faster toward the horizon.
 * @param tileCountMaxMinRatio Cap on tiles at high pitch versus pitch 0. Increasing it allows more
 *   tiles when pitched; if the ratio would be exceeded, zoom is reduced uniformly.
 */
@Immutable
public actual data class TileLodOptions(
  val maxZoomLevelsOnScreen: Double = 9.314,
  val tileCountMaxMinRatio: Double = 3.0,
) {
  public actual companion object Companion {
    public actual val Standard: TileLodOptions = TileLodOptions()

    public actual val Performance: TileLodOptions =
      TileLodOptions(maxZoomLevelsOnScreen = 11.0, tileCountMaxMinRatio = 1.5)

    public actual val HighDetail: TileLodOptions =
      TileLodOptions(maxZoomLevelsOnScreen = 4.0, tileCountMaxMinRatio = 8.0)
  }
}
