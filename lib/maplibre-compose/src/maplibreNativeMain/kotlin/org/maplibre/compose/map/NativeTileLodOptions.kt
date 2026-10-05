package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

@Immutable
public actual data class TileLodOptions
internal constructor(
  /**
   * The tile-detail algorithm and its parameters. To retain settings while editing an algorithm,
   * pass the previous value to its constructor.
   */
  public val algorithm: TileLodAlgorithm
) {
  public actual constructor(
    from: TileLodOptions,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(builder: Builder) : this(builder.algorithm)

  @MapOptionsDsl
  public actual class Builder internal constructor(from: TileLodOptions) {
    /** See [TileLodOptions.algorithm]. */
    public var algorithm: TileLodAlgorithm = from.algorithm
  }

  public actual companion object {
    public actual val Standard: TileLodOptions = TileLodOptions(TileLodAlgorithm.ScreenCenter())

    public actual val Performance: TileLodOptions =
      TileLodOptions(
        TileLodAlgorithm.ScreenCenter {
          minRadius = 2.0
          scale = 1.5
          pitchThreshold = 45.0
          zoomShift = -1.0
        }
      )

    public actual val HighDetail: TileLodOptions =
      TileLodOptions(
        TileLodAlgorithm.ScreenCenter {
          minRadius = 5.0
          pitchThreshold = 85.0
        }
      )
  }
}
