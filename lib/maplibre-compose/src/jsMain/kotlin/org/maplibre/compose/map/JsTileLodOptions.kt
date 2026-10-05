package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

@Immutable
public actual data class TileLodOptions
internal constructor(
  /**
   * Cap on distinct zooms when the horizon is on screen. Increasing it makes zoom decay faster
   * toward the horizon. No effect at pitch 0.
   */
  public val maxZoomLevelsOnScreen: Double,
  /**
   * Cap on tiles at high pitch versus pitch 0. Increasing it allows more tiles when pitched. If the
   * ratio would be exceeded, zoom is reduced uniformly. No effect at pitch 0.
   */
  public val tileCountMaxMinRatio: Double,
) {
  public actual constructor(
    from: TileLodOptions,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(builder.maxZoomLevelsOnScreen, builder.tileCountMaxMinRatio)

  @MapOptionsDsl
  public actual class Builder internal constructor(from: TileLodOptions) {
    /** See [TileLodOptions.maxZoomLevelsOnScreen]. */
    public var maxZoomLevelsOnScreen: Double = from.maxZoomLevelsOnScreen

    /** See [TileLodOptions.tileCountMaxMinRatio]. */
    public var tileCountMaxMinRatio: Double = from.tileCountMaxMinRatio
  }

  public actual companion object {
    public actual val Standard: TileLodOptions =
      TileLodOptions(maxZoomLevelsOnScreen = 9.314, tileCountMaxMinRatio = 3.0)

    public actual val Performance: TileLodOptions =
      TileLodOptions(maxZoomLevelsOnScreen = 11.0, tileCountMaxMinRatio = 1.5)

    public actual val HighDetail: TileLodOptions =
      TileLodOptions(maxZoomLevelsOnScreen = 4.0, tileCountMaxMinRatio = 8.0)
  }
}
