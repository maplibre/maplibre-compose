package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * How the map renders.
 *
 * Platform settings are available in source sets whose targets support them. The camera projection
 * is available on Android, iOS, and desktop.
 *
 * @property maximumFps Caps how often the map renders. Null uses the display refresh rate.
 * @property tileLod How the map chooses tile zoom when the camera is pitched.
 * @property debug Overlays that show how the map renders.
 */
@Immutable
public expect class RenderOptions {
  public val maximumFps: Int?
  public val tileLod: TileLodOptions
  public val debug: DebugOverlays

  /** Edits [from]; omitted settings inherit. */
  public constructor(from: RenderOptions = Standard, block: Builder.() -> Unit)

  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  override fun toString(): String

  @MapOptionsDsl
  public class Builder internal constructor(from: RenderOptions) {
    public var maximumFps: Int?
    public var tileLod: TileLodOptions

    public fun debug(block: DebugOverlays.Builder.() -> Unit)
  }

  public companion object {
    /** The platform defaults. */
    public val Standard: RenderOptions
    /** [Standard] with tile borders and collision boxes drawn. */
    public val Debug: RenderOptions
  }
}

/**
 * Overlays that show how the map renders. Platform overlays are available in source sets whose
 * targets support them.
 *
 * @property tileBorders Draws the boundary of every tile the map is built from.
 * @property collisionBoxes Draws the boxes symbol placement uses to decide what to hide.
 */
@Immutable
public expect class DebugOverlays {
  public val tileBorders: Boolean
  public val collisionBoxes: Boolean

  /** Edits [from]; omitted overlays inherit. */
  public constructor(from: DebugOverlays = None, block: Builder.() -> Unit)

  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  override fun toString(): String

  @MapOptionsDsl
  public class Builder internal constructor(from: DebugOverlays) {
    public var tileBorders: Boolean
    public var collisionBoxes: Boolean

    internal fun build(): DebugOverlays
  }

  internal companion object {
    val None: DebugOverlays
  }
}
