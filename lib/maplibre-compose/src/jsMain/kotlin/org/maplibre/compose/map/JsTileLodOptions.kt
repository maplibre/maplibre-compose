package org.maplibre.compose.map

/**
 * Cap on distinct zooms when the horizon is on screen. Increasing it makes zoom decay faster toward
 * the horizon. No effect at pitch 0.
 */
public var TileLodOptions.Builder.maxZoomLevelsOnScreen: Double
  get() = platform.maxZoomLevelsOnScreen
  set(value) {
    platform = platform.copy(maxZoomLevelsOnScreen = value)
  }

/**
 * Cap on tiles at high pitch versus pitch 0. Increasing it allows more tiles when pitched. If the
 * ratio would be exceeded, zoom is reduced uniformly. No effect at pitch 0.
 */
public var TileLodOptions.Builder.tileCountMaxMinRatio: Double
  get() = platform.tileCountMaxMinRatio
  set(value) {
    platform = platform.copy(tileCountMaxMinRatio = value)
  }

public val TileLodOptions.maxZoomLevelsOnScreen: Double
  get() = platform.maxZoomLevelsOnScreen

public val TileLodOptions.tileCountMaxMinRatio: Double
  get() = platform.tileCountMaxMinRatio

internal actual data class PlatformTileLodOptions(
  val maxZoomLevelsOnScreen: Double,
  val tileCountMaxMinRatio: Double,
) {
  actual constructor() : this(maxZoomLevelsOnScreen = 9.314, tileCountMaxMinRatio = 3.0)

  actual companion object {
    actual val Performance: PlatformTileLodOptions =
      PlatformTileLodOptions(maxZoomLevelsOnScreen = 11.0, tileCountMaxMinRatio = 1.5)
    actual val HighDetail: PlatformTileLodOptions =
      PlatformTileLodOptions(maxZoomLevelsOnScreen = 4.0, tileCountMaxMinRatio = 8.0)
  }
}
