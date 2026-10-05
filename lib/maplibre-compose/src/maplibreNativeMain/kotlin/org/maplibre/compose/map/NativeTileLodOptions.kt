package org.maplibre.compose.map

/**
 * The MapLibre Native tile-detail algorithm and its parameters. Available on Android, iOS, and
 * desktop. Replacing it replaces all algorithm settings. To retain settings while editing an
 * algorithm, pass the previous value to its constructor.
 */
public var TileLodOptions.Builder.algorithm: TileLodAlgorithm
  get() = platform.algorithm
  set(value) {
    platform = platform.copy(algorithm = value)
  }

/** The MapLibre Native tile-detail algorithm and its parameters. */
public val TileLodOptions.algorithm: TileLodAlgorithm
  get() = platform.algorithm

internal actual data class PlatformTileLodOptions(val algorithm: TileLodAlgorithm) {
  actual constructor() : this(TileLodAlgorithm.Default())

  actual companion object {
    actual val Performance: PlatformTileLodOptions =
      PlatformTileLodOptions(
        TileLodAlgorithm.Default {
          minRadius = 2.0
          scale = 1.5
          pitchThreshold = 45.0
          zoomShift = -1.0
        }
      )
    actual val HighDetail: PlatformTileLodOptions =
      PlatformTileLodOptions(
        TileLodAlgorithm.Default {
          minRadius = 5.0
          pitchThreshold = 85.0
        }
      )
  }
}
