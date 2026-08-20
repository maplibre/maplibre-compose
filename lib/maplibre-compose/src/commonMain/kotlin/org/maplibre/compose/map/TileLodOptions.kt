package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * How the map chooses tile zoom when the camera is pitched.
 *
 * At high pitch, farther ground covers more of the screen. The map can fetch coarser tiles toward
 * the horizon so it requests fewer of them. [Standard] is each backend's own default. [Performance]
 * fetches fewer tiles; [HighDetail] keeps more detail. The knobs behind those presets differ by
 * platform, so a custom configuration is written in expect/actual code.
 */
@Immutable
public expect class TileLodOptions {
  public companion object Companion {
    /** Each backend's own default tile cover. */
    public val Standard: TileLodOptions

    /** Fewer tiles when the camera is pitched, at the cost of coarser tiles toward the horizon. */
    public val Performance: TileLodOptions

    /** More tiles when the camera is pitched, keeping more detail toward the horizon. */
    public val HighDetail: TileLodOptions
  }
}
