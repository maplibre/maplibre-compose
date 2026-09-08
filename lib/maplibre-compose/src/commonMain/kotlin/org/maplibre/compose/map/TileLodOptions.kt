package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * How the map chooses tile zoom when the camera is pitched.
 *
 * At high pitch, farther ground covers more of the screen. The map can fetch coarser tiles toward
 * the horizon so it requests fewer of them. [Standard] is the platform default, [Performance]
 * fetches fewer tiles, and [HighDetail] keeps more detail. The settings behind the presets differ
 * by platform and are not available from common code.
 */
@Immutable
public class TileLodOptions private constructor(internal val platform: PlatformTileLodOptions) {
  /** Edits [from]; omitted settings inherit. */
  public constructor(
    from: TileLodOptions = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block).platform)

  override fun equals(other: Any?): Boolean = other is TileLodOptions && platform == other.platform

  override fun hashCode(): Int = platform.hashCode()

  @MapOptionsDsl
  public class Builder internal constructor(from: TileLodOptions) {
    internal var platform: PlatformTileLodOptions = from.platform
  }

  public companion object {
    /** The platform default. */
    public val Standard: TileLodOptions = TileLodOptions(PlatformTileLodOptions())

    /** Fewer tiles when the camera is pitched, at the cost of coarser tiles toward the horizon. */
    public val Performance: TileLodOptions = TileLodOptions(PlatformTileLodOptions.Performance)

    /** More tiles when the camera is pitched, keeping more detail toward the horizon. */
    public val HighDetail: TileLodOptions = TileLodOptions(PlatformTileLodOptions.HighDetail)
  }
}
