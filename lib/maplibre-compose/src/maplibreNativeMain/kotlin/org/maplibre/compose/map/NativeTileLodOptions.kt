package org.maplibre.compose.map

/** Which tile-cover algorithm to use. */
public var TileLodOptions.Builder.mode: TileLodMode
  get() = platform.mode
  set(value) {
    platform = platform.copy(mode = value)
  }

/**
 * Radius, in tile units, around the view point that always uses the finest zoom. Must be greater
 * than 1. Ignored in [TileLodMode.Distance].
 */
public var TileLodOptions.Builder.minRadius: Double
  get() = platform.minRadius
  set(value) {
    platform = platform.copy(minRadius = value)
  }

/** Scale on camera distance. Values greater than 1 coarsen tiles farther from the camera. */
public var TileLodOptions.Builder.scale: Double
  get() = platform.scale
  set(value) {
    platform = platform.copy(scale = value)
  }

/**
 * Camera pitch in degrees from nadir, matching [org.maplibre.compose.camera.CameraPosition.tilt],
 * above which level-of-detail reduction runs. 0 always reduces; 180 never reduces.
 */
public var TileLodOptions.Builder.pitchThreshold: Double
  get() = platform.pitchThreshold
  set(value) {
    platform = platform.copy(pitchThreshold = value)
  }

/**
 * Added to the zoom used for level of detail. `-1` cuts the tile count by about four. Ignored in
 * [TileLodMode.Distance].
 */
public var TileLodOptions.Builder.zoomShift: Double
  get() = platform.zoomShift
  set(value) {
    platform = platform.copy(zoomShift = value)
  }

public val TileLodOptions.mode: TileLodMode
  get() = platform.mode

public val TileLodOptions.minRadius: Double
  get() = platform.minRadius

public val TileLodOptions.scale: Double
  get() = platform.scale

public val TileLodOptions.pitchThreshold: Double
  get() = platform.pitchThreshold

public val TileLodOptions.zoomShift: Double
  get() = platform.zoomShift

internal actual data class PlatformTileLodOptions(
  val mode: TileLodMode,
  val minRadius: Double,
  val scale: Double,
  val pitchThreshold: Double,
  val zoomShift: Double,
) {
  actual constructor() :
    this(
      mode = TileLodMode.Default,
      minRadius = 3.0,
      scale = 1.0,
      pitchThreshold = 60.0,
      zoomShift = 0.0,
    )

  actual companion object {
    actual val Performance: PlatformTileLodOptions =
      PlatformTileLodOptions()
        .copy(minRadius = 2.0, scale = 1.5, pitchThreshold = 45.0, zoomShift = -1.0)
    actual val HighDetail: PlatformTileLodOptions =
      PlatformTileLodOptions().copy(minRadius = 5.0, pitchThreshold = 85.0)
  }
}
