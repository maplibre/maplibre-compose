package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * How the map chooses tile zoom when the camera is pitched.
 *
 * At high pitch, the view extends far toward the horizon. The map can fetch coarser tiles there so
 * it requests fewer of them. [Standard] is the engine default, [Performance] fetches fewer tiles,
 * and [HighDetail] keeps more detail.
 */
@Immutable
public expect class TileLodOptions {
  /** Edits [from]; omitted settings inherit. */
  public constructor(
    from: TileLodOptions = Standard,
    block: Builder.() -> Unit,
  )

  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  @MapOptionsDsl public class Builder

  public companion object {
    /** The engine default. */
    public val Standard: TileLodOptions

    /** Fewer tiles when the camera is pitched, at the cost of coarser tiles toward the horizon. */
    public val Performance: TileLodOptions

    /** More tiles when the camera is pitched, keeping more detail toward the horizon. */
    public val HighDetail: TileLodOptions
  }
}
